#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
GROUP_PAUSE_SECONDS="${GROUP_PAUSE_SECONDS:-10}"
SERVER_COUNT="${SERVER_COUNT:-1}"
SERVER_START_ID="${SERVER_START_ID:-}"

if [[ "${REPLICAS:-1}" != "1" && -z "$SERVER_START_ID" ]]; then
    echo "[ERROR] Set SERVER_START_ID to this machine's replica id, e.g., 0, 1, 2, ..." >&2
    exit 1
fi

SERVER_START_ID="${SERVER_START_ID:-0}"

for clients in $CLIENT_VALUES; do
    echo
    echo "============================================================"
    echo "[SUITE] Starting C2ORAM server group: clients=$clients"
    echo "============================================================"

    ROLE=server \
    CLIENTS="$clients" \
    LOCAL_CLIENTS="$clients" \
    SERVER_COUNT="$SERVER_COUNT" \
    SERVER_START_ID="$SERVER_START_ID" \
    SERVER_AUTO_STOP=1 \
    bash "$ROOT_DIR/run_our_multimachine_benchmark.sh"

    echo "[SUITE] Finished C2ORAM server group: clients=$clients"
    sleep "$GROUP_PAUSE_SECONDS"
done

echo "[SUITE] All C2ORAM server groups finished."
