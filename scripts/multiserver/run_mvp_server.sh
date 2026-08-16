#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
exec bash "$ROOT_DIR/MVPMultiServer/run_mvp_multimachine_clean_suite_server.sh"
