#!/usr/bin/env python3
"""Local web viewer for pipeline evaluation runs, stages, transcripts, patches, and diagnostics."""

from __future__ import annotations

import argparse
import difflib
import json
import mimetypes
import os
import re
import sqlite3
import subprocess
from functools import lru_cache
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

REPO_ROOT = Path(__file__).resolve().parent.parent
STATIC_DIR = Path(__file__).resolve().parent / "viewer"

STAGE_ORDER = [
    "ingestion",
    "triage",
    "synthesis",
    "application",
    "repair",
    "verification",
]

TEXT_EXTENSIONS = {
    ".json",
    ".md",
    ".txt",
    ".diff",
    ".patch",
    ".java",
    ".kt",
    ".xml",
    ".yaml",
    ".yml",
    ".log",
    ".properties",
}


def safe_json_loads(raw: Any, default: Any = None) -> Any:
    if raw is None:
        return default
    if isinstance(raw, (dict, list)):
        return raw
    if not isinstance(raw, str) or not raw.strip():
        return default
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return default


def connect_db(db_path: Path) -> sqlite3.Connection:
    if not db_path.exists():
        raise FileNotFoundError(f"Database not found: {db_path}")
    uri = f"file:{db_path.resolve()}?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=5.0)
    conn.row_factory = sqlite3.Row
    return conn


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [dict(r) for r in rows]


def resolve_cas_dir(cache_dir: Path, step_name: str, cas_key: str | None) -> Path | None:
    if not cas_key:
        return None
    candidate = cache_dir / step_name / cas_key
    if candidate.is_dir():
        return candidate
    return None


def list_cas_files(cas_dir: Path | None) -> list[dict[str, Any]]:
    if cas_dir is None or not cas_dir.is_dir():
        return []
    files: list[dict[str, Any]] = []
    for path in sorted(cas_dir.rglob("*")):
        if path.is_file():
            rel = path.relative_to(cas_dir).as_posix()
            files.append(
                {
                    "path": rel,
                    "size_bytes": path.stat().st_size,
                    "is_text": path.suffix.lower() in TEXT_EXTENSIONS or path.name.endswith("_src"),
                }
            )
    return files


def load_cas_transcript(cas_dir: Path | None) -> list[dict[str, Any]]:
    if cas_dir is None or not cas_dir.is_dir():
        return []
    for candidate in sorted(cas_dir.glob("*transcript*.json")):
        if candidate.is_file():
            parsed = safe_json_loads(candidate.read_text(encoding="utf-8", errors="replace"))
            if isinstance(parsed, list):
                return parsed
    return []


# ---------------------------------------------------------------------------
# Method-level diffs for annotation placements
# ---------------------------------------------------------------------------

PATCH_ARTIFACT_NAMES = (
    "annotations.patch",
    "repair.patch",
    "diff.patch",
    "patch.diff",
    "test_patch.diff",
)

JAVA_TYPE_RE = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
JAVA_ANNOTATION_RE = re.compile(r"@[A-Za-z_$][\w$.]*\s*(?:\([^()]*\))?")
JAVA_MODIFIER_RE = re.compile(
    r"^(?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp|"
    r"class|interface|enum|record)\b"
)


def mask_java_source(content: str) -> str:
    """Blank out comments, strings, and chars while preserving offsets and newlines.

    Working on the masked text lets the brace-depth and declaration scans ignore
    punctuation that lives inside comments or literals.
    """

    chars = list(content)
    n = len(chars)
    i = 0
    state: str | None = None
    while i < n:
        c = chars[i]
        nxt = chars[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                state = "line_comment"
                chars[i] = chars[i + 1] = " "
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block_comment"
                chars[i] = chars[i + 1] = " "
                i += 2
                continue
            if c == '"' and content.startswith('"""', i):
                state = "text_block"
                chars[i : i + 3] = [" ", " ", " "]
                i += 3
                continue
            if c == '"':
                state = "string"
                chars[i] = " "
                i += 1
                continue
            if c == "'":
                state = "char"
                chars[i] = " "
                i += 1
                continue
        else:
            if state == "line_comment":
                if c == "\n":
                    state = None
                else:
                    chars[i] = " "
                i += 1
                continue
            if state == "block_comment":
                if c == "*" and nxt == "/":
                    chars[i] = chars[i + 1] = " "
                    state = None
                    i += 2
                    continue
                if c != "\n":
                    chars[i] = " "
                i += 1
                continue
            if state == "text_block":
                if content.startswith('"""', i):
                    chars[i : i + 3] = [" ", " ", " "]
                    state = None
                    i += 3
                    continue
                if c != "\n":
                    chars[i] = " "
                i += 1
                continue
            if state in ("string", "char"):
                if c == "\\":
                    chars[i] = " "
                    if i + 1 < n:
                        chars[i + 1] = " "
                    i += 2
                    continue
                term = '"' if state == "string" else "'"
                if c == term:
                    state = None
                if c != "\n":
                    chars[i] = " "
                i += 1
                continue
        i += 1
    return "".join(chars)


class JavaSource:
    """Lightweight structural view of a Java source file.

    It tracks brace depth per line and exposes top-level type bodies plus their
    members, which is enough to locate the method/field/type containing a
    placement without running a full Java parser.
    """

    def __init__(self, content: str):
        self.content = content
        self.lines = content.splitlines()
        self.masked = mask_java_source(content)
        self.masked_lines = self.masked.splitlines()
        n = len(self.lines)
        self.depth_before = [0] * n
        self.depth_after = [0] * n
        events: list[tuple[str, int, int, int]] = []  # kind, line, depth, offset
        depth = 0
        line = 0
        for idx, ch in enumerate(self.masked):
            if ch == "\n":
                if line < n:
                    self.depth_after[line] = depth
                line += 1
                if line < n:
                    self.depth_before[line] = depth
                continue
            if ch == "{":
                events.append(("open", line, depth, idx))
                depth += 1
            elif ch == "}":
                depth -= 1
                events.append(("close", line, depth, idx))
        if n:
            self.depth_after[n - 1] = depth

        open_by_offset: dict[int, int] = {}
        stack: list[int] = []
        pairs: dict[int, int] = {}
        for event_idx, (kind, _line, _depth, offset) in enumerate(events):
            if kind == "open":
                stack.append(event_idx)
                open_by_offset[offset] = event_idx
            elif stack:
                pairs[stack.pop()] = event_idx

        self.types: list[dict[str, Any]] = []
        for match in JAVA_TYPE_RE.finditer(self.masked):
            kind, name = match.group(1), match.group(2)
            brace = self.masked.find("{", match.end())
            if brace < 0:
                continue
            open_idx = open_by_offset.get(brace)
            if open_idx is None:
                continue
            close_idx = pairs.get(open_idx)
            if close_idx is None:
                continue
            decl_line = self.masked.count("\n", 0, match.start())
            self.types.append(
                {
                    "kind": kind,
                    "name": name,
                    "decl_line": decl_line,
                    "open_line": events[open_idx][1],
                    "close_line": events[close_idx][1],
                    "member_depth": events[open_idx][2] + 1,
                }
            )
        self._members: dict[tuple[int, int, int], list[tuple[int, int]]] = {}

    def innermost_type(self, line: int) -> dict[str, Any] | None:
        candidates = [t for t in self.types if t["open_line"] <= line <= t["close_line"]]
        if not candidates:
            return None
        return max(candidates, key=lambda t: t["member_depth"])

    def type_after_line(self, line: int) -> dict[str, Any] | None:
        candidates = sorted((t for t in self.types if t["open_line"] >= line), key=lambda t: t["open_line"])
        for type_span in candidates:
            # A placement on a type annotation sits just above the declaration.
            # Accept the type only when everything between is annotation/modifier
            # or comment text, not unrelated statements.
            ok = True
            for idx in range(line, min(type_span["decl_line"] + 1, len(self.lines))):
                original = self.lines[idx].strip()
                masked = self.masked_lines[idx].strip()
                if not original:
                    continue
                if original.startswith(("@", "/*", "*", "//")):
                    continue
                if masked and JAVA_MODIFIER_RE.match(masked):
                    continue
                ok = False
                break
            if ok:
                return type_span
        return None

    def type_start_line(self, type_span: dict[str, Any]) -> int:
        start = type_span["decl_line"]
        idx = start - 1
        while idx >= 0:
            original = self.lines[idx].strip()
            masked = self.masked_lines[idx].strip()
            if not original:
                start = idx
                idx -= 1
                continue
            if original.startswith(("@", "/*", "*", "//")):
                start = idx
                idx -= 1
                continue
            if masked and JAVA_MODIFIER_RE.match(masked):
                start = idx
                idx -= 1
                continue
            break
        return start

    def members(self, type_span: dict[str, Any]) -> list[tuple[int, int]]:
        key = (type_span["open_line"], type_span["close_line"], type_span["member_depth"])
        if key in self._members:
            return self._members[key]

        limit = type_span["close_line"]
        depth = type_span["member_depth"]
        start_idx = type_span["open_line"] + 1
        if type_span["kind"] == "enum":
            # Skip the constant list so it does not get merged with the first
            # real member. Enum constants end at the optional top-level ';'.
            for idx in range(start_idx, limit):
                if self.depth_before[idx] == depth and self.masked_lines[idx].strip().endswith(";"):
                    start_idx = idx + 1
                    break

        result: list[tuple[int, int]] = []
        idx = start_idx
        while idx < limit:
            if self.depth_before[idx] != depth or not self.masked_lines[idx].strip():
                idx += 1
                continue
            start = idx
            end = None
            cursor = idx
            while cursor < limit:
                before = self.depth_before[cursor]
                after = self.depth_after[cursor]
                stripped = self.masked_lines[cursor].strip()
                if before < depth:
                    end = cursor - 1
                    break
                if before == depth:
                    if stripped.startswith("}"):
                        end = cursor - 1
                        break
                    if stripped.endswith(";") and after == depth:
                        end = cursor
                        break
                    if "{" in self.masked_lines[cursor] and after > depth:
                        close = cursor + 1
                        while close < limit and self.depth_before[close] > depth:
                            close += 1
                        end = close if close < limit else limit - 1
                        break
                cursor += 1
            if end is None:
                end = limit - 1
            result.append((start, end))
            idx = end + 1

        self._members[key] = result
        return result

    def member_for_line(self, type_span: dict[str, Any], line: int) -> tuple[int, int] | None:
        for span in self.members(type_span):
            if span[0] <= line <= span[1]:
                return span
        return None

    def member_info(self, span: tuple[int, int]) -> dict[str, Any]:
        start, end = span
        joined = " ".join(self.masked_lines[idx].strip() for idx in range(start, end + 1))
        cut = len(joined)
        for character in ("{", ";"):
            found = joined.find(character)
            if found >= 0:
                cut = min(cut, found)
        head = joined[:cut]
        no_annotations = JAVA_ANNOTATION_RE.sub(" ", head)

        type_match = re.search(r"\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)", no_annotations)
        if type_match:
            return {"kind": type_match.group(1), "name": type_match.group(2)}

        equals = no_annotations.find("=")
        paren = no_annotations.find("(")
        if paren >= 0 and (equals < 0 or equals > paren):
            method = re.search(r"([A-Za-z_$][\w$]*)\s*\(", no_annotations)
            return {"kind": "method", "name": method.group(1) if method else None}

        field_text = no_annotations.split("=")[0].replace(",", " ")
        identifiers = re.findall(r"[A-Za-z_$][\w$]*", field_text)
        return {"kind": "field", "name": identifiers[-1] if identifiers else None}


def find_placement_member(source: JavaSource, line_number: int) -> dict[str, Any] | None:
    """Return the method, field, or type header that contains a 1-based line."""

    line = line_number - 1
    type_span = source.innermost_type(line)
    if type_span is None:
        type_span = source.type_after_line(line)
        if type_span is None:
            return None
        start = source.type_start_line(type_span)
        end = type_span["open_line"]
        return {
            "kind": type_span["kind"],
            "name": type_span["name"],
            "start": start,
            "end": end,
            "type_level": True,
            "type_name": type_span["name"],
            "type_kind": type_span["kind"],
            "type_depth": type_span["member_depth"],
        }

    span = source.member_for_line(type_span, line)
    if span is None:
        return None
    info = source.member_info(span)
    return {
        "kind": info["kind"],
        "name": info["name"],
        "start": span[0],
        "end": span[1],
        "type_level": False,
        "type_name": type_span["name"],
        "type_kind": type_span["kind"],
        "type_depth": type_span["member_depth"],
    }


def find_old_member(
    source: JavaSource, new_member: dict[str, Any], mapped_old_line: int | None
) -> dict[str, Any] | None:
    """Find the corresponding member in the pre-stage source."""

    if new_member["type_level"]:
        candidates = [
            t
            for t in source.types
            if t["name"] == new_member["type_name"] and t["kind"] == new_member["type_kind"]
        ]
        if not candidates:
            return None
        type_span = min(candidates, key=lambda t: abs(t["open_line"] - (mapped_old_line or 0)))
        return {
            "kind": type_span["kind"],
            "name": type_span["name"],
            "start": source.type_start_line(type_span),
            "end": type_span["open_line"],
        }

    old_type = None
    if mapped_old_line is not None:
        candidate = source.innermost_type(mapped_old_line)
        if candidate is not None and candidate["name"] == new_member["type_name"]:
            old_type = candidate
    if old_type is None:
        for candidate in source.types:
            if (
                candidate["name"] == new_member["type_name"]
                and candidate["member_depth"] == new_member["type_depth"]
                and candidate["kind"] == new_member["type_kind"]
            ):
                old_type = candidate
                break
    if old_type is None:
        return None

    best: tuple[int, tuple[int, int]] | None = None
    for span in source.members(old_type):
        info = source.member_info(span)
        if info["kind"] != new_member["kind"] or info["name"] != new_member["name"]:
            continue
        if mapped_old_line is not None and span[0] <= mapped_old_line <= span[1]:
            return {"start": span[0], "end": span[1], "kind": info["kind"], "name": info["name"]}
        distance = abs(span[0] - (mapped_old_line or 0))
        if best is None or distance < best[0]:
            best = (distance, span)

    if best is not None:
        return {
            "start": best[1][0],
            "end": best[1][1],
            "kind": new_member["kind"],
            "name": new_member["name"],
        }

    if mapped_old_line is not None:
        span = source.member_for_line(old_type, mapped_old_line)
        if span is not None:
            info = source.member_info(span)
            return {"start": span[0], "end": span[1], "kind": info["kind"], "name": info["name"]}
    return None


def clean_diff_path(path: str) -> str:
    path = path.strip().strip('"')
    if path.startswith("a/") or path.startswith("b/"):
        path = path[2:]
    return path


def parse_diff_sections(patch_text: str) -> list[dict[str, Any]]:
    """Parse a unified diff into per-file sections with hunk rows."""

    lines = patch_text.splitlines()
    sections: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    idx = 0
    while idx < len(lines):
        line = lines[idx]
        if line.startswith("diff --git "):
            rest = line[len("diff --git ") :]
            if " b/" in rest:
                old_part, new_part = rest.split(" b/", 1)
                old_path = clean_diff_path(old_part)
                new_path = clean_diff_path("b/" + new_part)
            else:
                parts = rest.split()
                old_path = clean_diff_path(parts[0]) if parts else None
                new_path = clean_diff_path(parts[-1]) if len(parts) > 1 else old_path
            current = {"old_path": old_path, "new_path": new_path, "hunks": []}
            sections.append(current)
            idx += 1
            continue

        if line.startswith("--- ") and idx + 1 < len(lines) and lines[idx + 1].startswith("+++ "):
            old_path = clean_diff_path(lines[idx][4:].split("\t")[0].strip())
            new_path = clean_diff_path(lines[idx + 1][4:].split("\t")[0].strip())
            if current is None or current["hunks"]:
                current = {"old_path": old_path, "new_path": new_path, "hunks": []}
                sections.append(current)
            else:
                current["old_path"] = old_path
                current["new_path"] = new_path
            idx += 2
            continue

        if line.startswith("@@"):
            match = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
            if current is None:
                current = {"old_path": None, "new_path": None, "hunks": []}
                sections.append(current)
            if not match:
                idx += 1
                continue
            hunk = {
                "old_start": int(match.group(1)),
                "old_count": int(match.group(2) or 1),
                "new_start": int(match.group(3)),
                "new_count": int(match.group(4) or 1),
                "rows": [],
            }
            current["hunks"].append(hunk)
            idx += 1
            old_remaining = hunk["old_count"]
            new_remaining = hunk["new_count"]
            while idx < len(lines) and (old_remaining > 0 or new_remaining > 0):
                row = lines[idx]
                if row.startswith("\\"):
                    idx += 1
                    continue
                if row.startswith("+"):
                    hunk["rows"].append(("add", row[1:]))
                    new_remaining -= 1
                elif row.startswith("-"):
                    hunk["rows"].append(("del", row[1:]))
                    old_remaining -= 1
                elif row.startswith(" ") or row == "":
                    hunk["rows"].append(("ctx", row[1:] if row.startswith(" ") else ""))
                    old_remaining -= 1
                    new_remaining -= 1
                else:
                    break
                idx += 1
            while idx < len(lines) and lines[idx].startswith("\\"):
                idx += 1
            continue

        idx += 1
    return sections


def apply_diff_section(lines: list[str], section: dict[str, Any], reverse: bool = False) -> list[str]:
    """Apply the section's hunks to a list of lines (or undo them when reversed)."""

    result: list[str] = []
    cursor = 0
    for hunk in section["hunks"]:
        start = (hunk["new_start"] if reverse else hunk["old_start"]) - 1
        if start > cursor:
            result.extend(lines[cursor:start])
        cursor = max(start, cursor)
        for row_type, text in hunk["rows"]:
            if reverse:
                row_type = {"add": "del", "del": "add", "ctx": "ctx"}[row_type]
            if row_type == "ctx":
                if cursor < len(lines):
                    result.append(lines[cursor])
                cursor += 1
            elif row_type == "del":
                cursor += 1
            elif row_type == "add":
                result.append(text)
    result.extend(lines[cursor:])
    return result


def map_new_line_to_old(section: dict[str, Any], new_line: int) -> int | None:
    """Map a 1-based new-file line back to the old file (added lines use the insertion point)."""

    for hunk in section["hunks"]:
        old_line = hunk["old_start"]
        new_line_cursor = hunk["new_start"]
        for row_type, _text in hunk["rows"]:
            if row_type == "ctx":
                if new_line_cursor == new_line:
                    return old_line
                old_line += 1
                new_line_cursor += 1
            elif row_type == "del":
                old_line += 1
            elif row_type == "add":
                if new_line_cursor == new_line:
                    return old_line
                new_line_cursor += 1
    return None


def make_member_diff(
    old_lines: list[str],
    new_lines: list[str],
    path: str,
    old_start: int,
    new_start: int,
) -> str:
    """Build a unified diff with file-accurate hunk line numbers."""

    raw = list(
        difflib.unified_diff(
            old_lines,
            new_lines,
            fromfile=f"a/{path}",
            tofile=f"b/{path}",
            lineterm="",
            n=3,
        )
    )
    adjusted: list[str] = []
    for line in raw:
        if line.startswith("@@"):
            match = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$", line)
            if match:
                old_count = match.group(2) or "1"
                new_count = match.group(4) or "1"
                line = (
                    f"@@ -{int(match.group(1)) + old_start},{old_count} "
                    f"+{int(match.group(3)) + new_start},{new_count} @@{match.group(5)}"
                )
        adjusted.append(line)
    return "\n".join(adjusted)


def is_safe_relative_path(path: str) -> bool:
    candidate = Path(path)
    return bool(path) and not candidate.is_absolute() and ".." not in candidate.parts


def find_stage_patch(
    cache_dir: Path, step_name: str, cas_key: str | None, diff_patch: str | None
) -> str | None:
    if diff_patch:
        return diff_patch
    cas_dir = resolve_cas_dir(cache_dir, step_name, cas_key)
    if cas_dir is None:
        return None
    for name in PATCH_ARTIFACT_NAMES:
        candidate = cas_dir / name
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8", errors="replace")
    return None


def run_git(args: list[str], timeout: int = 20) -> tuple[int, str]:
    try:
        completed = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return completed.returncode, completed.stdout
    except (OSError, subprocess.SubprocessError):
        return 1, ""


@lru_cache(maxsize=256)
def git_show(repo: str, commit: str, path: str) -> str | None:
    code, output = run_git(["git", "-C", repo, "show", f"{commit}:{path}"])
    return output if code == 0 else None


def repo_candidates(run_row: dict[str, Any], eval_repos_dir: Path) -> list[Path]:
    seen: set[str] = set()
    candidates: list[Path] = []

    def add(path: Path | None) -> None:
        if path is None:
            return
        key = str(path)
        if key not in seen and path.is_dir():
            seen.add(key)
            candidates.append(path)

    git_url = run_row.get("git_url")
    if git_url:
        url_path = Path(git_url)
        add(url_path if url_path.is_absolute() else REPO_ROOT / url_path)
        add(eval_repos_dir / url_path.name)
        add(eval_repos_dir / f"{url_path.name}.git")
    repo_name = run_row.get("repo_name")
    if repo_name:
        add(eval_repos_dir / repo_name)
        add(eval_repos_dir / f"{repo_name}.git")
    return candidates


def find_repo_for_commit(run_row: dict[str, Any], eval_repos_dir: Path, commit: str) -> Path | None:
    if not commit:
        return None
    checked: set[str] = set()
    for repo in repo_candidates(run_row, eval_repos_dir):
        checked.add(str(repo))
        code, _ = run_git(["git", "-C", str(repo), "cat-file", "-e", f"{commit}^{{commit}}"])
        if code == 0:
            return repo
    if eval_repos_dir.is_dir():
        for entry in sorted(eval_repos_dir.iterdir()):
            if not entry.is_dir() or str(entry) in checked:
                continue
            code, _ = run_git(["git", "-C", str(entry), "cat-file", "-e", f"{commit}^{{commit}}"])
            if code == 0:
                return entry
    return None


def base_commit_for_run(run_row: dict[str, Any], repo: Path | None) -> str | None:
    parent = run_row.get("parent_commit")
    if parent:
        return str(parent)
    commit = run_row.get("commit_hash")
    if repo is not None and commit:
        code, output = run_git(["git", "-C", str(repo), "rev-parse", f"{commit}^"])
        if code == 0:
            return output.strip()
    return str(commit) if commit else None


def worktree_source_file(run_id: str, step_name: str, file_path: str) -> Path | None:
    if not is_safe_relative_path(file_path):
        return None
    base = (REPO_ROOT / ".worktrees" / f"run_{run_id}" / step_name).resolve()
    target = (base / file_path).resolve()
    if not target.is_relative_to(base) or not target.is_file():
        return None
    return target


def join_source_lines(lines: list[str]) -> str:
    return "\n".join(lines) + ("\n" if lines else "")


def build_placement_diff(
    db_path: Path,
    cache_dir: Path,
    run_id: str,
    step_id: str,
    placement_id: str,
) -> dict[str, Any] | None:
    """Reconstruct a method-level diff for one annotation placement.

    The stage patch is applied to the run's parent commit to recover the new
    source, and the member containing the placement is diffed against its
    counterpart in the pre-stage source. Generated files that never appear in
    the patch fall back to a read-only snippet with an explanatory note.
    """

    with connect_db(db_path) as conn:
        placement_row = conn.execute(
            "SELECT * FROM annotation_placements WHERE placement_id = ? AND run_id = ? AND step_id = ?",
            (placement_id, run_id, step_id),
        ).fetchone()
        if placement_row is None:
            return None
        step_row = conn.execute(
            "SELECT * FROM step_trajectories WHERE step_id = ? AND run_id = ?",
            (step_id, run_id),
        ).fetchone()
        if step_row is None:
            return None
        run_row = conn.execute(
            """
            SELECT
                r.*,
                c.parent_commit,
                repo.name AS repo_name,
                repo.git_url
            FROM evaluation_runs r
            LEFT JOIN commits c ON r.commit_hash = c.commit_hash
            LEFT JOIN repositories repo ON c.repo_id = repo.repo_id
            WHERE r.run_id = ?
            """,
            (run_id,),
        ).fetchone()
        if run_row is None:
            return None

    placement = dict(placement_row)
    step = dict(step_row)
    run = dict(run_row)
    file_path = str(placement["file_path"])
    line_number = int(placement["line_number"])
    result: dict[str, Any] = {
        "placement_id": placement["placement_id"],
        "file_path": file_path,
        "line_number": line_number,
        "symbol": placement["symbol"],
        "qualifier": placement["qualifier"],
        "has_diff": False,
        "diff": "",
    }

    if not is_safe_relative_path(file_path):
        result["note"] = "Placement path is not a safe repository-relative path."
        return result

    config_snapshot = safe_json_loads(run.get("config_snapshot"), {}) or {}
    paths_config = config_snapshot.get("paths") or {}
    eval_repos_dir = Path(paths_config.get("eval_repos_dir") or REPO_ROOT / "eval-repos")
    if not eval_repos_dir.is_dir():
        eval_repos_dir = REPO_ROOT / "eval-repos"

    commit = run.get("commit_hash") or ""
    repo = find_repo_for_commit(run, eval_repos_dir, commit)
    old_content = None
    if repo is not None:
        base_commit = base_commit_for_run(run, repo)
        if base_commit:
            old_content = git_show(str(repo), base_commit, file_path)

    worktree_file = worktree_source_file(run_id, step["step_name"], file_path)
    worktree_content = worktree_file.read_text(encoding="utf-8", errors="replace") if worktree_file else None

    patch_text = find_stage_patch(cache_dir, step["step_name"], step.get("cas_key"), step.get("diff_patch"))
    section = None
    if patch_text:
        for candidate in parse_diff_sections(patch_text):
            if file_path in (candidate["old_path"], candidate["new_path"]):
                section = candidate
                break

    # Prefer replaying the recorded patch so each attempt is diffed against its
    # own result. The worktree is only needed for generated files that never
    # appear in the patch.
    new_content = None
    if old_content is not None and section is not None:
        new_content = join_source_lines(apply_diff_section(old_content.splitlines(), section))
    elif worktree_content is not None:
        new_content = worktree_content
    elif old_content is not None:
        new_content = old_content

    if old_content is None and new_content is not None and section is not None:
        old_content = join_source_lines(apply_diff_section(new_content.splitlines(), section, reverse=True))

    if new_content is None:
        result["note"] = "Could not reconstruct the annotated source for this placement."
        return result

    new_source = JavaSource(new_content)
    new_member = find_placement_member(new_source, line_number)
    if new_member is None:
        result["note"] = "Could not locate the enclosing method or field for this placement."
        result["snippet_start"] = max(1, line_number - 8)
        result["snippet"] = "\n".join(
            new_source.lines[max(0, line_number - 9) : min(len(new_source.lines), line_number + 8)]
        )
        return result

    result["member_kind"] = new_member["kind"]
    result["member_name"] = new_member["name"]

    if old_content is None:
        result["snippet_start"] = new_member["start"] + 1
        result["snippet"] = "\n".join(new_source.lines[new_member["start"] : new_member["end"] + 1])
        if section is None:
            result["note"] = (
                "This file is not part of the stage patch. It may be generated during the build, "
                "so there is no pre-stage baseline to diff against."
            )
        else:
            result["note"] = "Could not recover the pre-stage source for this file."
        return result

    old_source = JavaSource(old_content)
    mapped_old_line = map_new_line_to_old(section, line_number) if section is not None else None
    old_member = find_old_member(old_source, new_member, mapped_old_line)
    if old_member is None:
        result["snippet_start"] = new_member["start"] + 1
        result["snippet"] = "\n".join(new_source.lines[new_member["start"] : new_member["end"] + 1])
        result["note"] = "Could not match this member in the pre-stage source."
        return result

    old_snippet = old_source.lines[old_member["start"] : old_member["end"] + 1]
    new_snippet = new_source.lines[new_member["start"] : new_member["end"] + 1]
    diff = make_member_diff(
        old_snippet,
        new_snippet,
        file_path,
        old_member["start"],
        new_member["start"],
    )
    result["old_start"] = old_member["start"] + 1
    result["new_start"] = new_member["start"] + 1
    result["has_diff"] = bool(diff.strip())
    result["diff"] = diff
    if not result["has_diff"]:
        result["snippet_start"] = new_member["start"] + 1
        result["snippet"] = "\n".join(new_snippet)
        result["note"] = (
            "This file is not part of the stage patch."
            if section is None
            else "This member has no textual changes in the stage patch."
        )
    return result


MAX_LIVE_READ_BYTES = 8 * 1024 * 1024


def read_live_transcript(path: Path, after: int) -> dict[str, Any]:
    """Return complete JSONL events written to ``path`` at or after byte ``after``.

    Only whole lines (ending in a newline) are returned, so a line the pipeline
    is mid-way through writing is picked up on a later poll. If the file is
    shorter than ``after`` it was truncated or replaced; the read restarts at 0
    and ``reset`` tells the client to discard what it had.
    """
    try:
        size = path.stat().st_size
    except FileNotFoundError:
        return {"exists": False, "events": [], "next_offset": after, "reset": False}

    reset = after > size
    start = 0 if reset or after < 0 else after
    with path.open("rb") as fh:
        fh.seek(start)
        chunk = fh.read(MAX_LIVE_READ_BYTES)
    end = chunk.rfind(b"\n")
    if end < 0:
        return {"exists": True, "events": [], "next_offset": start, "reset": reset}
    complete = chunk[: end + 1]
    events = []
    for line in complete.decode("utf-8", errors="replace").splitlines():
        parsed = safe_json_loads(line)
        if isinstance(parsed, dict):
            events.append(parsed)
    return {"exists": True, "events": events, "next_offset": start + len(complete), "reset": reset}


def extract_key_config(config: dict[str, Any], run_row: dict[str, Any]) -> dict[str, Any]:
    model_cfg = config.get("model") or {}
    triage_cfg = config.get("triage") or {}
    agent_cfg = config.get("agent") or {}
    steps_cfg = agent_cfg.get("steps") or {}
    synth_cfg = config.get("synthesis") or {}
    repair_cfg = config.get("repair") or {}
    guard_cfg = config.get("guardrails") or {}
    verif_cfg = config.get("verification") or {}
    sample_cfg = config.get("sample") or {}
    prompt_cfg = config.get("prompt") or {}
    checker_cfg = config.get("checker") or {}

    return {
        "triage_mode": triage_cfg.get("mode"),
        "triage_depth": triage_cfg.get("depth"),
        "triage_limit": triage_cfg.get("limit"),
        "triage_bugfix_threshold": triage_cfg.get("bugfix_threshold"),
        "model_default": model_cfg.get("default"),
        "model_triage": model_cfg.get("triage"),
        "model_synthesis": model_cfg.get("synthesis"),
        "model_application": model_cfg.get("application"),
        "model_repair": model_cfg.get("repair"),
        "guardrails_mode": run_row.get("guardrails_mode") or guard_cfg.get("mode"),
        "verification_scope": run_row.get("verification_scope") or verif_cfg.get("scope"),
        "verification_baseline": verif_cfg.get("baseline"),
        "sample_index": run_row.get("sample_index"),
        "sample_temperature": run_row.get("sample_temperature", sample_cfg.get("temperature")),
        "synthesis_freedom_level": synth_cfg.get("freedom_level"),
        "synthesis_allowed_checkers": synth_cfg.get("allowed_checkers"),
        "repair_local_var_annotations": repair_cfg.get("local_variable_annotations"),
        "repair_internet_access": repair_cfg.get("internet_access"),
        "prompt_tell_non_triviality_error": prompt_cfg.get("tell_non_triviality_error"),
        "agent_steps": steps_cfg,
        "agent_timeout_ms": agent_cfg.get("timeout_ms"),
        "agent_max_checker_invocations": agent_cfg.get("max_checker_invocations"),
        "checker_processor": checker_cfg.get("processor"),
    }


def fetch_runs_list(db_path: Path) -> list[dict[str, Any]]:
    with connect_db(db_path) as conn:
        cols = {row["name"] for row in conn.execute("PRAGMA table_info(evaluation_runs)").fetchall()}
        batch_col = "r.batch_id" if "batch_id" in cols else "NULL AS batch_id"
        owner_col = "r.owner_pid" if "owner_pid" in cols else "NULL AS owner_pid"
        runs = rows_to_dicts(
            conn.execute(
                f"""
                SELECT
                    r.run_id,
                    {batch_col},
                    {owner_col},
                    r.benchmark_id,
                    r.commit_hash,
                    r.sample_index,
                    r.sample_temperature,
                    r.guardrails_mode,
                    r.verification_scope,
                    r.status,
                    r.started_at,
                    r.completed_at,
                    r.config_snapshot,
                    v.verdict,
                    v.outcome_kind,
                    v.remaining_type_errors,
                    v.unit_tests_passed,
                    v.reg_test_passed,
                    v.skipped_tests,
                    c.pr_number,
                    c.pr_title,
                    c.triage_status,
                    c.classification,
                    c.bugfix_score,
                    c.discard_reason,
                    c.regression_test_class,
                    c.regression_test_method,
                    repo.name AS repo_name
                FROM evaluation_runs r
                LEFT JOIN v_evaluation_verdicts v ON r.run_id = v.run_id
                LEFT JOIN commits c ON r.commit_hash = c.commit_hash
                LEFT JOIN repositories repo ON c.repo_id = repo.repo_id
                ORDER BY r.started_at DESC, r.run_id DESC
                """
            ).fetchall()
        )

        steps_by_run: dict[str, list[dict[str, Any]]] = {}
        for s in rows_to_dicts(
            conn.execute(
                """
                SELECT
                    st.run_id, st.step_id, st.step_number, st.attempt_number, st.step_name,
                    st.success, st.cache_hit, st.started_at, st.completed_at, st.compiler_diagnostics,
                    vs.turns_taken, vs.total_tokens, vs.checker_invocations,
                    vs.test_invocations, vs.checker_diagnostic_count
                FROM step_trajectories st
                LEFT JOIN v_step_summaries vs ON st.step_id = vs.step_id
                ORDER BY st.step_number ASC, st.attempt_number ASC
                """
            ).fetchall()
        ):
            steps_by_run.setdefault(s["run_id"], []).append(s)

        llm_by_run: dict[str, dict[str, Any]] = {}
        for row in rows_to_dicts(
            conn.execute(
                """
                SELECT
                    run_id,
                    COUNT(*) AS llm_calls,
                    COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                    COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                    COALESCE(SUM(total_tokens), 0) AS total_tokens,
                    COALESCE(SUM(latency_ms), 0) AS total_latency_ms
                FROM llm_invocations
                WHERE run_id IS NOT NULL
                GROUP BY run_id
                """
            ).fetchall()
        ):
            llm_by_run[row["run_id"]] = row

        # Stage-level retries are run-wide; keep them separate from the
        # per-attempt/session information attached to each step.
        retry_by_run: dict[str, dict[str, int]] = {}
        for row in rows_to_dicts(
            conn.execute(
                """
                SELECT run_id,
                       SUM(CASE WHEN will_retry=1 THEN 1 ELSE 0 END) AS stage_retries,
                       SUM(CASE WHEN will_retry=0 THEN 1 ELSE 0 END) AS final_stage_failures
                FROM retry_events GROUP BY run_id
                """
            ).fetchall()
        ):
            retry_by_run[row["run_id"]] = row

        result = []
        for r in runs:
            cfg = safe_json_loads(r.pop("config_snapshot", None), {}) or {}
            run_id = r["run_id"]
            r["key_config"] = extract_key_config(cfg, r)
            r["steps"] = steps_by_run.get(run_id, [])
            reconcile_run_status(r)
            mark_in_progress(r["steps"], r.get("status"))
            r["llm_stats"] = llm_by_run.get(
                run_id,
                {
                    "llm_calls": 0,
                    "prompt_tokens": 0,
                    "completion_tokens": 0,
                    "total_tokens": 0,
                    "total_latency_ms": 0,
                },
            )
            retries = retry_by_run.get(run_id, {})
            r["stage_retries"] = retries.get("stage_retries", 0)
            r["final_stage_failures"] = retries.get("final_stage_failures", 0)
            result.append(r)
        return result


def fetch_run_detail(db_path: Path, cache_dir: Path, logs_dir: Path, run_id: str) -> dict[str, Any] | None:
    with connect_db(db_path) as conn:
        run_row = conn.execute(
            """
            SELECT
                r.*,
                v.verdict,
                v.outcome_kind,
                v.remaining_type_errors,
                v.unit_tests_passed,
                v.reg_test_passed,
                v.skipped_tests,
                c.repo_id,
                c.parent_commit,
                c.author_date,
                c.commit_msg,
                c.pr_number,
                c.pr_title,
                c.pr_body,
                c.classification,
                c.bugfix_score,
                c.regression_tests_found,
                c.test_methods_extracted,
                c.regression_test_class,
                c.regression_test_method,
                c.test_patch AS commit_test_patch,
                c.triage_status,
                c.discard_reason,
                c.compiler_error_log AS commit_compiler_error_log,
                repo.name AS repo_name,
                repo.git_url,
                repo.default_branch
            FROM evaluation_runs r
            LEFT JOIN v_evaluation_verdicts v ON r.run_id = v.run_id
            LEFT JOIN commits c ON r.commit_hash = c.commit_hash
            LEFT JOIN repositories repo ON c.repo_id = repo.repo_id
            WHERE r.run_id = ?
            """,
            (run_id,),
        ).fetchone()

        if run_row is None:
            return None

        run = dict(run_row)
        reconcile_run_status(run)
        config_snapshot = safe_json_loads(run.get("config_snapshot"), {}) or {}
        run["config_snapshot"] = config_snapshot
        run["key_config"] = extract_key_config(config_snapshot, run)

        steps = rows_to_dicts(
            conn.execute(
                """
                SELECT
                    st.*,
                    vs.turns_taken,
                    vs.total_prompt_tokens,
                    vs.total_completion_tokens,
                    vs.total_tokens,
                    vs.checker_invocations,
                    vs.test_invocations,
                    vs.checker_diagnostic_count
                FROM step_trajectories st
                LEFT JOIN v_step_summaries vs ON st.step_id = vs.step_id
                WHERE st.run_id = ?
                ORDER BY st.step_number ASC, st.attempt_number ASC
                """,
                (run_id,),
            ).fetchall()
        )

        # Group related records by step_id
        def group_by_step(table: str, order_by: str) -> dict[str, list[dict[str, Any]]]:
            grouped: dict[str, list[dict[str, Any]]] = {}
            for row in rows_to_dicts(
                conn.execute(f"SELECT * FROM {table} WHERE run_id = ? ORDER BY {order_by}", (run_id,)).fetchall()
            ):
                grouped.setdefault(row["step_id"], []).append(row)
            return grouped

        synthesis_runs = group_by_step("synthesis_runs", "attempt_number ASC, created_at ASC")
        qualifiers = group_by_step("synthesized_qualifiers", "created_at ASC, qualifier_name ASC")
        helpers = group_by_step("synthesized_helpers", "created_at ASC, helper_name ASC")
        placements = group_by_step("annotation_placements", "file_path ASC, line_number ASC")
        checker_runs = group_by_step("checker_runs", "attempt_number ASC, created_at ASC")
        checker_diagnostics = group_by_step("checker_diagnostics", "COALESCE(turn_index, 0) ASC, file_path ASC, line_number ASC")
        test_runs = group_by_step("test_runs", "attempt_number ASC, created_at ASC")
        test_executions = group_by_step("test_executions", "is_regression_test DESC, test_class ASC, test_method ASC")
        compilation_events = group_by_step("compilation_events", "turn_index ASC, created_at ASC")
        guardrail_events = group_by_step("guardrail_events", "turn_index ASC, created_at ASC")
        agent_turns = group_by_step("agent_turns", "turn_index ASC, created_at ASC")
        llm_invocations = group_by_step("llm_invocations", "COALESCE(turn_index, 0) ASC, created_at ASC")
        try:
            run["subprocess_metrics"] = rows_to_dicts(conn.execute(
                """SELECT stage, kind, duration_ms, exit_code, timed_out
                   FROM subprocess_metrics WHERE run_id = ?
                   ORDER BY created_at DESC LIMIT 50""", (run_id,)
            ).fetchall())
        except sqlite3.Error:
            run["subprocess_metrics"] = []

        # Discarded agent sessions carry both counters explicitly, so they are
        # the one place where stage attempts and internal (session) retries can
        # be told apart without inferring from a transcript. Tolerate older DBs.
        discarded_by_step: dict[str, list[dict[str, Any]]] = {}
        try:
            for row in rows_to_dicts(conn.execute(
                """SELECT step_id, stage, stage_attempt_number, internal_attempt_number,
                          termination, discard_check, feedback, agent_failure_message,
                          exit_code, steps_counted, timed_out
                   FROM discarded_attempts WHERE run_id = ?
                   ORDER BY stage, stage_attempt_number, internal_attempt_number""",
                (run_id,),
            ).fetchall()):
                discarded_by_step.setdefault(row["step_id"], []).append(row)
        except sqlite3.Error:
            pass

        for step in steps:
            sid = step["step_id"]
            step["artifacts_manifest"] = safe_json_loads(step.get("artifacts_manifest"), None)
            step["agent_transcript"] = safe_json_loads(step.get("agent_transcript"), None)

            cas_dir = resolve_cas_dir(cache_dir, step["step_name"], step.get("cas_key"))
            step["cas_dir_exists"] = cas_dir is not None
            step["cas_files"] = list_cas_files(cas_dir)
            step["cas_transcript"] = load_cas_transcript(cas_dir)

            if step.get("cache_hit") and step.get("cas_key"):
                origin = conn.execute(
                    """
                    SELECT st.run_id, st.step_id, st.started_at, r.benchmark_id
                    FROM step_trajectories st
                    JOIN evaluation_runs r ON st.run_id = r.run_id
                    WHERE st.step_name = ? AND st.cas_key = ? AND st.cache_hit = 0
                    ORDER BY st.created_at ASC, st.step_id ASC
                    LIMIT 1
                    """,
                    (step["step_name"], step["cas_key"]),
                ).fetchone()
                step["cache_origin"] = dict(origin) if origin is not None else None
            else:
                step["cache_origin"] = None

            # Fallback patch from CAS (e.g., triage test_patch.diff) if diff_patch is empty
            if not step.get("diff_patch") and cas_dir is not None:
                for diff_name in ("diff.patch", "patch.diff", "test_patch.diff"):
                    diff_file = cas_dir / diff_name
                    if diff_file.is_file():
                        step["cas_diff_patch"] = diff_file.read_text(encoding="utf-8", errors="replace")
                        step["cas_diff_name"] = diff_name
                        break

            # Load placement_guide.md and triage_metadata.json inline if available
            if cas_dir is not None and (cas_dir / "placement_guide.md").is_file():
                step["placement_guide"] = (cas_dir / "placement_guide.md").read_text(encoding="utf-8", errors="replace")
            if cas_dir is not None and (cas_dir / "triage_metadata.json").is_file():
                step["triage_metadata"] = safe_json_loads(
                    (cas_dir / "triage_metadata.json").read_text(encoding="utf-8", errors="replace"),
                    None,
                )

            turns = agent_turns.get(sid, [])
            for t in turns:
                t["tool_arguments_parsed"] = safe_json_loads(t.get("tool_arguments"), None)

            step["synthesis_runs"] = synthesis_runs.get(sid, [])
            step["synthesized_qualifiers"] = qualifiers.get(sid, [])
            step["synthesized_helpers"] = helpers.get(sid, [])
            step["annotation_placements"] = placements.get(sid, [])
            step["checker_runs"] = checker_runs.get(sid, [])
            step["checker_diagnostics"] = checker_diagnostics.get(sid, [])
            step["test_runs"] = test_runs.get(sid, [])
            step["test_executions"] = test_executions.get(sid, [])
            step["compilation_events"] = compilation_events.get(sid, [])
            step["guardrail_events"] = guardrail_events.get(sid, [])
            step["agent_turns"] = turns
            step["llm_invocations"] = llm_invocations.get(sid, [])
            step["discarded_attempts"] = discarded_by_step.get(sid, [])

        # Sort steps canonically by step_number, then STAGE_ORDER
        order_map = {name: i for i, name in enumerate(STAGE_ORDER)}
        steps.sort(key=lambda s: (s.get("step_number") or 0, order_map.get(s.get("step_name", ""), 99), s.get("attempt_number") or 1))

        log_file = logs_dir / f"{run_id}.log"
        run["log_available"] = log_file.is_file()
        run["log_size_bytes"] = log_file.stat().st_size if log_file.is_file() else 0
        mark_in_progress(steps, run.get("status"))
        run["steps"] = steps
        try:
            run["retry_events"] = rows_to_dicts(conn.execute(
                """SELECT step_id, stage, error_class, attempt_number, max_attempts, will_retry,
                          delay_ms, error_message, created_at
                   FROM retry_events WHERE run_id = ? ORDER BY created_at, attempt_number""",
                (run_id,),
            ).fetchall())
        except sqlite3.Error:
            run["retry_events"] = []
        return run


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True  # exists, owned by another user
    except OSError:
        return False
    return True


def reconcile_run_status(run: dict[str, Any]) -> None:
    """Show a run whose pipeline process is gone as interrupted.

    The pipeline marks its runs interrupted from a shutdown hook, but a hard
    kill (SIGKILL, crash, power loss) leaves the row `running` until the next
    pipeline start reconciles it. The viewer is read-only, so it applies the
    same owner_pid liveness rule as markStaleRunningRuns to what it displays.
    """
    if run.get("status") != "running":
        return
    pid = run.get("owner_pid")
    if pid is None or not pid_alive(int(pid)):
        run["status"] = "interrupted"


def live_run_ids(db_path: Path) -> list[str]:
    """Runs still `running` after liveness reconciliation.

    A hard-killed pipeline writes nothing, so the DB mtime alone never tells the
    page to refresh; the client also reloads when this list changes.
    """
    try:
        with connect_db(db_path) as conn:
            cols = {row["name"] for row in conn.execute("PRAGMA table_info(evaluation_runs)").fetchall()}
            owner_col = "owner_pid" if "owner_pid" in cols else "NULL AS owner_pid"
            rows = rows_to_dicts(conn.execute(
                f"SELECT run_id, status, {owner_col} FROM evaluation_runs WHERE status='running'"
            ).fetchall())
    except (sqlite3.Error, FileNotFoundError):
        return []
    for r in rows:
        reconcile_run_status(r)
    return sorted(r["run_id"] for r in rows if r["status"] == "running")


def mark_in_progress(steps: list[dict[str, Any]], run_status: str | None) -> None:
    """Flag steps that are genuinely executing.

    A step row is written at step start with success=0 and no completed_at. If
    the stage throws or the process dies, that row is never finalized, so an
    unfinished step only counts as in progress while its run is still running.
    """
    for st in steps:
        st["in_progress"] = (
            run_status == "running" and not st.get("completed_at") and not st.get("success")
        )


class ViewerHandler(BaseHTTPRequestHandler):
    db_path: Path
    cache_dir: Path
    logs_dir: Path
    transcripts_dir: Path

    def log_message(self, format: str, *args: Any) -> None:
        # Keep stdout clean unless debugging
        pass

    def send_json(self, payload: Any, status: int = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_text(self, text: str, content_type: str = "text/plain; charset=utf-8", status: int = HTTPStatus.OK) -> None:
        body = text.encode("utf-8", errors="replace")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_static_file(self, rel_path: str) -> None:
        clean = rel_path.lstrip("/") or "index.html"
        target = (STATIC_DIR / clean).resolve()
        if not target.is_relative_to(STATIC_DIR.resolve()) or not target.is_file():
            self.send_error(HTTPStatus.NOT_FOUND, "Not found")
            return
        mime, _ = mimetypes.guess_type(str(target))
        content = target.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", f"{mime or 'application/octet-stream'}; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        try:
            if path == "/api/health":
                # The pipeline DB runs in WAL mode: writes land in the -wal file and
                # leave the main file's mtime unchanged until a checkpoint.
                mtimes = [
                    p.stat().st_mtime
                    for p in (self.db_path, Path(f"{self.db_path}-wal"))
                    if p.exists()
                ]
                mtime = max(mtimes) if mtimes else None
                self.send_json({
                    "ok": True,
                    "db_path": str(self.db_path),
                    "db_mtime": mtime,
                    "live_runs": live_run_ids(self.db_path),
                })
                return

            if path == "/api/runs":
                self.send_json({"runs": fetch_runs_list(self.db_path)})
                return

            if path == "/api/telemetry":
                with connect_db(self.db_path) as conn:
                    payload = {"db_operations": [], "retries": [], "subprocesses": [], "recent_retries": [], "notes": []}
                    for key, sql in (
                        ("db_operations", "SELECT operation, calls, failures, avg_duration_ms, max_duration_ms FROM v_db_operation_summary ORDER BY avg_duration_ms DESC"),
                        ("retries", "SELECT stage, error_class, attempts, retries FROM v_retry_summary ORDER BY attempts DESC"),
                        ("subprocesses", "SELECT stage, kind, calls, failures, timeouts, avg_duration_ms, max_duration_ms, total_duration_ms FROM v_subprocess_summary ORDER BY total_duration_ms DESC"),
                    ):
                        try:
                            payload[key] = rows_to_dicts(conn.execute(sql).fetchall())
                        except sqlite3.Error:
                            payload["notes"].append(f"{key.replace('_', ' ')} unavailable (database may predate telemetry views).")
                    try:
                        payload["recent_retries"] = rows_to_dicts(conn.execute(
                            """SELECT stage, error_class, attempt_number, will_retry, delay_ms, error_message
                               FROM retry_events ORDER BY created_at DESC LIMIT 20"""
                        ).fetchall())
                    except sqlite3.Error:
                        payload["notes"].append("retry event details unavailable (retry_events table missing).")
                self.send_json(payload)
                return

            if path.startswith("/api/runs/"):
                parts = [unquote(p) for p in path[len("/api/runs/"):].split("/") if p]
                if len(parts) == 1:
                    run_id = parts[0]
                    detail = fetch_run_detail(self.db_path, self.cache_dir, self.logs_dir, run_id)
                    if detail is None:
                        self.send_json({"error": f"Run not found: {run_id}"}, HTTPStatus.NOT_FOUND)
                    else:
                        self.send_json(detail)
                    return

                if len(parts) == 2 and parts[1] == "log":
                    run_id = parts[0]
                    # Prevent path traversal in run_id
                    if "/" in run_id or "\\" in run_id or ".." in run_id:
                        self.send_json({"error": "Invalid run_id"}, HTTPStatus.BAD_REQUEST)
                        return
                    log_file = (self.logs_dir / f"{run_id}.log").resolve()
                    if not log_file.is_relative_to(self.logs_dir.resolve()) or not log_file.is_file():
                        self.send_json({"error": "Log file not found"}, HTTPStatus.NOT_FOUND)
                        return
                    text = log_file.read_text(encoding="utf-8", errors="replace")
                    self.send_json({"run_id": run_id, "content": text, "size_bytes": len(text.encode("utf-8"))})
                    return

                if len(parts) == 4 and parts[1] == "steps" and parts[3] == "placement-diff":
                    run_id = parts[0]
                    step_id = parts[2]
                    placement_id = (query.get("placement_id") or [""])[0]
                    if not placement_id:
                        self.send_json({"error": "Missing placement_id"}, HTTPStatus.BAD_REQUEST)
                        return
                    payload = build_placement_diff(
                        self.db_path, self.cache_dir, run_id, step_id, placement_id
                    )
                    if payload is None:
                        self.send_json({"error": "Placement not found"}, HTTPStatus.NOT_FOUND)
                    else:
                        self.send_json(payload)
                    return

                if len(parts) == 4 and parts[1] == "steps" and parts[3] == "transcript":
                    run_id, step_id = parts[0], parts[2]
                    for ident in (run_id, step_id):
                        if not ident or "/" in ident or "\\" in ident or ".." in ident:
                            self.send_json({"error": "Invalid id"}, HTTPStatus.BAD_REQUEST)
                            return
                    try:
                        after = int((query.get("after") or ["0"])[0])
                    except ValueError:
                        self.send_json({"error": "Invalid offset"}, HTTPStatus.BAD_REQUEST)
                        return
                    base = self.transcripts_dir.resolve()
                    target = (base / run_id / f"{step_id}.jsonl").resolve()
                    if not target.is_relative_to(base):
                        self.send_json({"error": "Invalid id"}, HTTPStatus.BAD_REQUEST)
                        return
                    self.send_json(read_live_transcript(target, after))
                    return

                if len(parts) >= 4 and parts[1] == "steps" and parts[3] == "artifact":
                    run_id = parts[0]
                    step_id = parts[2]
                    artifact_rel = "/".join(parts[4:])
                    if not artifact_rel:
                        self.send_json({"error": "Missing artifact path"}, HTTPStatus.BAD_REQUEST)
                        return
                    with connect_db(self.db_path) as conn:
                        row = conn.execute(
                            "SELECT step_name, cas_key FROM step_trajectories WHERE run_id = ? AND step_id = ?",
                            (run_id, step_id),
                        ).fetchone()
                    if row is None:
                        self.send_json({"error": "Step not found"}, HTTPStatus.NOT_FOUND)
                        return
                    cas_dir = resolve_cas_dir(self.cache_dir, row["step_name"], row["cas_key"])
                    if cas_dir is None:
                        self.send_json({"error": "CAS directory not found"}, HTTPStatus.NOT_FOUND)
                        return
                    target = (cas_dir / artifact_rel).resolve()
                    if not target.is_relative_to(cas_dir.resolve()) or not target.is_file():
                        self.send_json({"error": "Artifact file not found"}, HTTPStatus.NOT_FOUND)
                        return
                    if target.stat().st_size > 2 * 1024 * 1024:
                        self.send_json({"error": "Artifact too large (>2MB) to preview inline"}, HTTPStatus.BAD_REQUEST)
                        return
                    content = target.read_text(encoding="utf-8", errors="replace")
                    self.send_json({"path": artifact_rel, "content": content, "size_bytes": target.stat().st_size})
                    return

            if path == "/" or not path.startswith("/api/"):
                self.send_static_file("index.html" if path == "/" else path)
                return

            self.send_json({"error": "Not found"}, HTTPStatus.NOT_FOUND)
        except FileNotFoundError as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self.send_json({"error": f"{type(exc).__name__}: {exc}"}, HTTPStatus.INTERNAL_SERVER_ERROR)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run viewer for llm-type-annotation-synthesis")
    parser.add_argument("--db", default=str(REPO_ROOT / "data" / "pipeline.db"), help="Path to SQLite database")
    parser.add_argument("--cache-dir", default=str(REPO_ROOT / ".cache" / "stages"), help="Path to CAS stages directory")
    parser.add_argument("--logs-dir", default=str(REPO_ROOT / "logs" / "runs"), help="Path to per-run logs directory")
    parser.add_argument(
        "--transcripts-dir",
        default=str(REPO_ROOT / "logs" / "transcripts"),
        help="Path to live (in-progress) agent transcript streams",
    )
    parser.add_argument("--bind", default=os.environ.get("VIEWER_BIND", "127.0.0.1"), help="Bind address")
    parser.add_argument("--port", type=int, default=int(os.environ.get("VIEWER_PORT", "8002")), help="Port number")
    args = parser.parse_args()

    handler_cls = type(
        "ConfiguredViewerHandler",
        (ViewerHandler,),
        {
            "db_path": Path(args.db),
            "cache_dir": Path(args.cache_dir),
            "logs_dir": Path(args.logs_dir),
            "transcripts_dir": Path(args.transcripts_dir),
        },
    )

    server = ThreadingHTTPServer((args.bind, args.port), handler_cls)
    print(f"Pipeline Run Viewer listening on http://{args.bind}:{args.port}")
    print(f"  DB:        {args.db}")
    print(f"  CAS cache: {args.cache_dir}")
    print(f"  Logs:      {args.logs_dir}")
    print(f"  Live:      {args.transcripts_dir}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down viewer.")
        server.server_close()


if __name__ == "__main__":
    main()
