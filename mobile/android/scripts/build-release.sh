#!/usr/bin/env bash
set -euo pipefail
# Credentials are passed through the environment, never expanded into shell trace output.
set +x
release_script_dir="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$release_script_dir/release.py" "$@"
