#!/usr/bin/env bash
# Launch the Datasette browser UI for the pipeline database.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB="${1:-data/pipeline.db}"

if ! command -v datasette >/dev/null 2>&1; then
  echo "datasette is not installed. Install it with: pip install datasette" >&2
  exit 1
fi

if [[ ! -f "$DB" ]]; then
  echo "Database not found: $DB" >&2
  echo "Run the pipeline first, or pass the path to an existing SQLite database." >&2
  exit 1
fi

exec datasette "$DB" \
  --bind "${DATASETTE_BIND:-127.0.0.1}" \
  --port "${DATASETTE_PORT:-8001}" \
  --metadata "$SCRIPT_DIR/datasette-metadata.json"
