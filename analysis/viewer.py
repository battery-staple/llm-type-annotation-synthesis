#!/usr/bin/env python3
"""Local web viewer for pipeline evaluation runs, stages, transcripts, patches, and diagnostics."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sqlite3
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


def extract_key_config(config: dict[str, Any], run_row: dict[str, Any]) -> dict[str, Any]:
    model_cfg = config.get("model") or {}
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
        runs = rows_to_dicts(
            conn.execute(
                """
                SELECT
                    r.run_id,
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
                    v.remaining_type_errors,
                    v.unit_tests_passed,
                    v.reg_test_passed,
                    v.skipped_tests,
                    c.pr_number,
                    c.pr_title,
                    c.triage_status,
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
                SELECT run_id, step_id, step_number, attempt_number, step_name, success, cache_hit, started_at, completed_at
                FROM step_trajectories
                ORDER BY step_number ASC, attempt_number ASC
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

        result = []
        for r in runs:
            cfg = safe_json_loads(r.pop("config_snapshot", None), {}) or {}
            run_id = r["run_id"]
            r["key_config"] = extract_key_config(cfg, r)
            r["steps"] = steps_by_run.get(run_id, [])
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
            result.append(r)
        return result


def fetch_run_detail(db_path: Path, cache_dir: Path, logs_dir: Path, run_id: str) -> dict[str, Any] | None:
    with connect_db(db_path) as conn:
        run_row = conn.execute(
            """
            SELECT
                r.*,
                v.verdict,
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

            # Load placement_guide.md inline if available
            if cas_dir is not None and (cas_dir / "placement_guide.md").is_file():
                step["placement_guide"] = (cas_dir / "placement_guide.md").read_text(encoding="utf-8", errors="replace")

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

        # Sort steps canonically by step_number, then STAGE_ORDER
        order_map = {name: i for i, name in enumerate(STAGE_ORDER)}
        steps.sort(key=lambda s: (s.get("step_number") or 0, order_map.get(s.get("step_name", ""), 99), s.get("attempt_number") or 1))

        log_file = logs_dir / f"{run_id}.log"
        run["log_available"] = log_file.is_file()
        run["log_size_bytes"] = log_file.stat().st_size if log_file.is_file() else 0
        run["steps"] = steps
        return run


class ViewerHandler(BaseHTTPRequestHandler):
    db_path: Path
    cache_dir: Path
    logs_dir: Path

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
                mtime = self.db_path.stat().st_mtime if self.db_path.exists() else None
                self.send_json({"ok": True, "db_path": str(self.db_path), "db_mtime": mtime})
                return

            if path == "/api/runs":
                self.send_json({"runs": fetch_runs_list(self.db_path)})
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
        },
    )

    server = ThreadingHTTPServer((args.bind, args.port), handler_cls)
    print(f"Pipeline Run Viewer listening on http://{args.bind}:{args.port}")
    print(f"  DB:        {args.db}")
    print(f"  CAS cache: {args.cache_dir}")
    print(f"  Logs:      {args.logs_dir}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down viewer.")
        server.server_close()


if __name__ == "__main__":
    main()
