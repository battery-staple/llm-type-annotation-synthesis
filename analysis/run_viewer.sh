#!/usr/bin/env bash
# Launch the local Pipeline Run Viewer web UI.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DB="${1:-$REPO_ROOT/data/pipeline.db}"

exec python3 "$SCRIPT_DIR/viewer.py" \
  --db "$DB" \
  --cache-dir "${VIEWER_CACHE_DIR:-$REPO_ROOT/.cache/stages}" \
  --logs-dir "${VIEWER_LOGS_DIR:-$REPO_ROOT/logs/runs}" \
  --transcripts-dir "${VIEWER_TRANSCRIPTS_DIR:-$REPO_ROOT/logs/transcripts}" \
  --bind "${VIEWER_BIND:-127.0.0.1}" \
  --port "${VIEWER_PORT:-8002}"
