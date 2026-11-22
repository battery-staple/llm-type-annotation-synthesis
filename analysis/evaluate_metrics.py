#!/usr/bin/env python3
"""Print offline pipeline metrics from the SQLite telemetry database.

Uses only the Python standard library. Example:
    python3 analysis/evaluate_metrics.py --db data/pipeline.db
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path
from typing import Iterable, Sequence


def connect(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise SystemExit(f"Database not found: {path}")
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def query(connection: sqlite3.Connection, sql: str, params: Sequence = ()) -> list[sqlite3.Row]:
    try:
        return connection.execute(sql, params).fetchall()
    except sqlite3.Error as exc:
        print(f"[skipped: {exc}]", file=sys.stderr)
        return []


def print_table(rows: Iterable[sqlite3.Row], columns: Sequence[str] | None = None) -> None:
    rows = list(rows)
    if not rows:
        print("  (no rows)")
        return
    if columns is None:
        columns = list(rows[0].keys())
    widths = {column: len(column) for column in columns}
    for row in rows:
        for column in columns:
            widths[column] = max(widths[column], len(format_value(row[column])))
    header = "  ".join(column.ljust(widths[column]) for column in columns)
    print("  " + header)
    print("  " + "  ".join("-" * widths[column] for column in columns))
    for row in rows:
        print("  " + "  ".join(format_value(row[column]).ljust(widths[column]) for column in columns))


def format_value(value: object) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, float):
        return f"{value:g}"
    return str(value)


def section(title: str) -> None:
    print()
    print(title)
    print("=" * len(title))


def has_view(connection: sqlite3.Connection, name: str) -> bool:
    return connection.execute("SELECT 1 FROM sqlite_master WHERE type='view' AND name=?", (name,)).fetchone() is not None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default="data/pipeline.db", help="path to the SQLite database")
    args = parser.parse_args()

    connection = connect(Path(args.db))

    section("Run lifecycle / outcome kinds")
    if has_view(connection, "v_evaluation_verdicts"):
        print_table(query(connection, """
            SELECT outcome_kind, status, COUNT(*) AS runs
            FROM v_evaluation_verdicts GROUP BY outcome_kind, status
            ORDER BY outcome_kind, status
        """))
    else:
        print("  Lifecycle summary unavailable (v_evaluation_verdicts view missing).")

    section("Evaluation verdicts (v_evaluation_verdicts)")
    print_table(query(connection, """
        SELECT run_id, benchmark_id, status,
               CASE WHEN status = 'triage_only' THEN 'triage_only'
                    WHEN status = 'success' AND completed_at IS NOT NULL THEN 'evaluated'
                    ELSE 'not_evaluated' END AS outcome_kind,
               CASE WHEN status = 'triage_only' THEN 'not evaluated (limit)' ELSE verdict END AS verdict,
               remaining_type_errors, reg_test_passed, unit_tests_passed, skipped_tests
        FROM v_evaluation_verdicts
        ORDER BY benchmark_id, run_id
    """))

    section("Database operation performance")
    if has_view(connection, "v_db_operation_summary"):
        print_table(query(connection, """
            SELECT operation, calls, failures, avg_duration_ms, max_duration_ms
            FROM v_db_operation_summary ORDER BY avg_duration_ms DESC
        """))
    else:
        print("  Unavailable: this database predates v_db_operation_summary.")

    section("Retry telemetry")
    if has_view(connection, "v_retry_summary"):
        print_table(query(connection, """
            SELECT stage, error_class, attempts, retries,
                   attempts - retries AS final_failures
            FROM v_retry_summary ORDER BY attempts DESC
        """))
    else:
        print("  Unavailable: this database predates v_retry_summary.")
    if connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='retry_events'").fetchone():
        print("  Recent retry events (delay and message):")
        print_table(query(connection, """
            SELECT stage, error_class, attempt_number, max_attempts, will_retry,
                   delay_ms, error_message
            FROM retry_events ORDER BY created_at DESC LIMIT 30
        """))
    else:
        print("  Detailed retry delays/messages unavailable (retry_events table missing).")

    section("Step summaries (v_step_summaries)")
    print_table(query(connection, """
        SELECT run_id, step_number, attempt_number, step_name, success, cache_hit,
               turns_taken, total_prompt_tokens, total_completion_tokens, total_tokens,
               checker_invocations, test_invocations
        FROM v_step_summaries
        ORDER BY run_id, step_number, attempt_number
    """))

    section("Token totals")
    totals = query(connection, """
        SELECT COUNT(*) AS calls,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(total_tokens), 0) AS total_tokens
        FROM llm_invocations
    """)
    print_table(totals)
    print()
    print("  By stage:")
    print_table(query(connection, """
        SELECT stage, COUNT(*) AS calls,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(total_tokens), 0) AS total_tokens
        FROM llm_invocations
        GROUP BY stage
        ORDER BY total_tokens DESC
    """))

    section("Guardrail breakdown")
    print_table(query(connection, """
        SELECT violation_category, guard_type, action_blocked, COUNT(*) AS events
        FROM guardrail_events
        GROUP BY violation_category, guard_type, action_blocked
        ORDER BY events DESC, violation_category
    """))
    print()
    print("  By run and stage:")
    print_table(query(connection, """
        SELECT run_id, stage, violation_category, COUNT(*) AS events
        FROM guardrail_events
        GROUP BY run_id, stage, violation_category
        ORDER BY run_id, events DESC
    """))

    section("Final checker errors")
    print_table(query(connection, """
        WITH latest AS (
            SELECT run_id, checker_run_id, error_count, warning_count,
                   checker_invoked, success,
                   ROW_NUMBER() OVER (
                       PARTITION BY run_id
                       ORDER BY attempt_number DESC, created_at DESC, checker_run_id DESC
                   ) AS rn
            FROM checker_runs
            WHERE stage = 'verification_final'
        )
        SELECT run_id, error_count, warning_count, checker_invoked, success
        FROM latest
        WHERE rn = 1
        ORDER BY run_id
    """))
    print()
    print("  Diagnostics from the latest final checker run:")
    print_table(query(connection, """
        WITH latest AS (
            SELECT run_id, checker_run_id,
                   ROW_NUMBER() OVER (
                       PARTITION BY run_id
                       ORDER BY attempt_number DESC, created_at DESC, checker_run_id DESC
                   ) AS rn
            FROM checker_runs
            WHERE stage = 'verification_final'
        )
        SELECT d.run_id, d.severity, d.file_path, d.line_number, d.message
        FROM checker_diagnostics d
        JOIN latest l
          ON l.run_id = d.run_id AND l.checker_run_id = d.checker_run_id
        WHERE l.rn = 1
        ORDER BY d.run_id, d.file_path, d.line_number
    """))

    connection.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
