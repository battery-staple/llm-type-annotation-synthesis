#!/usr/bin/env python3
"""Inspect the pipeline CAS cache without starting a run.

Examples:
    python3 analysis/inspect_cache.py
    python3 analysis/inspect_cache.py --stage synthesis
    python3 analysis/inspect_cache.py --key 3e1509ca --details
    python3 analysis/inspect_cache.py --orphaned
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path
from typing import Any


def human_size(value: int) -> str:
    size = float(value)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.1f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024
    return f"{int(value)} B"


def read_manifest(path: Path) -> dict[str, Any] | None:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def load_db(path: Path) -> dict[str, dict[str, Any]]:
    if not path.is_file():
        return {}
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    rows = con.execute(
        """
        SELECT s.cas_key, s.run_id, s.step_name, s.attempt_number,
               s.cache_hit, s.success, s.started_at,
               r.benchmark_id, r.sample_index, r.status, r.config_snapshot
        FROM step_trajectories s
        LEFT JOIN evaluation_runs r ON r.run_id = s.run_id
        """
    ).fetchall()
    by_key: dict[str, dict[str, Any]] = {}
    model_cache: dict[str, str] = {}
    for row in rows:
        key = row["cas_key"]
        entry = by_key.setdefault(key, {"runs": {}, "hits": 0})
        entry["hits"] += 1 if row["cache_hit"] else 0
        run = entry["runs"].setdefault(
            row["run_id"],
            {
                "benchmark": row["benchmark_id"],
                "sample_index": row["sample_index"],
                "run_status": row["status"],
                "hit": False,
                "model": "",
            },
        )
        run["hit"] = run["hit"] or bool(row["cache_hit"])
        if row["run_id"] not in model_cache:
            model = ""
            try:
                model = json.loads(row["config_snapshot"] or "{}").get("model", {}).get("default", "")
            except ValueError:
                pass
            model_cache[row["run_id"]] = model
        run["model"] = model_cache[row["run_id"]]
    return by_key


def scan(cache: Path, db: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    db_by_key = load_db(db)
    entries: list[dict[str, Any]] = []
    summary: dict[str, Any] = {}
    if not cache.is_dir():
        return entries, summary

    for stage_dir in sorted(p for p in cache.iterdir() if p.is_dir() and not p.name.startswith(".")):
        stage = stage_dir.name
        stats = summary.setdefault(
            stage, {"entries": 0, "success": 0, "discard": 0, "staging": 0, "bytes": 0}
        )
        staging = [p for p in stage_dir.iterdir() if p.is_dir() and p.name.startswith(".staging-")]
        stats["staging"] = len(staging)
        for key_dir in sorted(p for p in stage_dir.iterdir() if p.is_dir() and not p.name.startswith(".")):
            manifest_path = key_dir / "manifest.json"
            manifest = read_manifest(manifest_path)
            if manifest is None:
                stats["staging"] += 1
                continue
            artifacts = manifest.get("artifacts") or []
            total_bytes = sum(int(a.get("size_bytes", 0)) for a in artifacts)
            success = bool(manifest.get("success", True))
            stats["entries"] += 1
            stats["success" if success else "discard"] += 1
            stats["bytes"] += total_bytes
            db_info = db_by_key.get(key_dir.name, {"runs": {}, "hits": 0})
            entries.append(
                {
                    "stage": stage,
                    "key": manifest.get("cache_key", key_dir.name),
                    "success": success,
                    "sample_index": manifest.get("sample_index"),
                    "temperature": manifest.get("sample_temperature"),
                    "created_at": manifest.get("created_at", ""),
                    "artifacts": artifacts,
                    "bytes": total_bytes,
                    "db": db_info,
                }
            )
    entries.sort(key=lambda e: (e["created_at"], e["stage"], e["key"]), reverse=True)
    return entries, summary


def run_text(entry: dict[str, Any]) -> str:
    runs = entry["db"].get("runs") or {}
    if not runs:
        return "-"
    parts = []
    for run_id, info in sorted(runs.items()):
        marker = "*" if info.get("hit") else ""
        parts.append(f"{run_id[:8]}{marker}")
    return ", ".join(parts)


def print_table(rows: list[list[str]], headers: list[str]) -> None:
    if not rows:
        print("  (none)")
        return
    widths = [len(h) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], len(cell))
    print("  " + "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers)))
    print("  " + "  ".join("-" * widths[i] for i in range(len(headers))))
    for row in rows:
        print("  " + "  ".join(cell.ljust(widths[i]) for i, cell in enumerate(row)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default=".cache/stages", help="CAS stage directory")
    parser.add_argument("--db", default="data/pipeline.db", help="pipeline SQLite database")
    parser.add_argument("--stage", help="only this stage")
    parser.add_argument("--key", help="only keys beginning with this prefix")
    parser.add_argument("--run", help="only entries associated with this run id")
    parser.add_argument("--details", action="store_true", help="show manifest and run details")
    parser.add_argument("--full-keys", action="store_true", help="print full 64-character keys")
    parser.add_argument("--orphaned", action="store_true", help="only entries never referenced by the DB")
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    args = parser.parse_args()

    cache = Path(args.cache)
    entries, summary = scan(cache, Path(args.db))
    if args.stage:
        entries = [e for e in entries if e["stage"] == args.stage]
    if args.key:
        entries = [e for e in entries if e["key"].startswith(args.key)]
    if args.run:
        entries = [e for e in entries if args.run in (e["db"].get("runs") or {})]
    if args.orphaned:
        entries = [e for e in entries if not e["db"]["runs"]]

    if args.json:
        print(json.dumps({"summary": summary, "entries": entries}, indent=2))
        return 0

    print(f"Cache: {cache.resolve()}")
    print()
    stage_rows = []
    for stage, stats in sorted(summary.items()):
        stage_rows.append(
            [
                stage,
                str(stats["entries"]),
                str(stats["success"]),
                str(stats["discard"]),
                str(stats["staging"]),
                human_size(stats["bytes"]),
            ]
        )
    print("Stages")
    print("======")
    print_table(stage_rows, ["stage", "entries", "ok", "discard", "staging", "size"])

    print()
    print(f"Entries ({len(entries)})")
    print("=================")
    rows = []
    for entry in entries:
        key = entry["key"] if args.full_keys else entry["key"][:12]
        rows.append(
            [
                entry["stage"],
                key,
                str(entry["sample_index"]),
                f"{float(entry['temperature']):g}" if entry["temperature"] is not None else "?",
                entry["created_at"][:19],
                "ok" if entry["success"] else "discard",
                str(len(entry["artifacts"])),
                human_size(entry["bytes"]),
                run_text(entry),
            ]
        )
    print_table(rows, ["stage", "key", "seed", "temp", "created", "status", "files", "size", "runs"])
    print()
    print("runs: first 8 chars; * = at least one cache hit (as opposed to the publishing run)")

    if args.details:
        for entry in entries:
            print()
            print(f"=== {entry['stage']}/{entry['key']} ===")
            print(f"seed={entry['sample_index']} temperature={entry['temperature']} success={entry['success']}")
            print(f"created={entry['created_at']} size={human_size(entry['bytes'])}")
            print("artifacts:")
            for artifact in entry["artifacts"]:
                print(
                    f"  {artifact.get('path'):<40} {human_size(int(artifact.get('size_bytes', 0))):>10}  "
                    f"{artifact.get('sha256', '')[:16]}"
                )
            runs = entry["db"].get("runs") or {}
            if runs:
                print("runs:")
                for run_id, info in sorted(runs.items()):
                    print(
                        f"  {run_id}  {info.get('benchmark')}  seed={info.get('sample_index')}  "
                        f"{info.get('model')}  status={info.get('run_status')}  hit={info.get('hit')}"
                    )
            else:
                print("runs: (orphaned cache entry)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
