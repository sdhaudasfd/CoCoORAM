#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
CLEAN_PAUSE_SECONDS="${CLEAN_PAUSE_SECONDS:-15}"
GROUP_PAUSE_SECONDS="${GROUP_PAUSE_SECONDS:-20}"
SERVER_COUNT="${SERVER_COUNT:-1}"
SERVER_START_ID="${SERVER_START_ID:-}"

if [[ "${REPLICAS:-1}" != "1" && -z "$SERVER_START_ID" ]]; then
    echo "[ERROR] Set SERVER_START_ID to this machine's replica id, e.g., 0, 1, 2, ..." >&2
    exit 1
fi

SERVER_START_ID="${SERVER_START_ID:-0}"

cleanup_local_mvp() {
    echo "[CLEAN] stopping old local MVP Java processes"
    pkill -f 'oram\.server\.ORAMServer|oram\.single\.server\.ORAMSingleServer|oram\.benchmark\.MultiServerBenchmarkClient|oram\.benchmark\.SingleServerBenchmarkClient' 2>/dev/null || true
    sleep 2
    pkill -9 -f 'oram\.server\.ORAMServer|oram\.single\.server\.ORAMSingleServer|oram\.benchmark\.MultiServerBenchmarkClient|oram\.benchmark\.SingleServerBenchmarkClient' 2>/dev/null || true

    echo "[CLEAN] removing local BFT runtime state"
    find "$ROOT_DIR" -name currentView -print -delete 2>/dev/null || true
    find "$ROOT_DIR/build/install/MVPORAM" -type d \( \
        -name runtime -o -name state -o -name log -o -name logs -o \
        -name checkpoint -o -name checkpoints \
    \) -print -exec rm -rf {} + 2>/dev/null || true

    sleep "$CLEAN_PAUSE_SECONDS"
}

for clients in $CLIENT_VALUES; do
    echo
    echo "============================================================"
    echo "[CLEAN-SUITE] Starting MVP server group: clients=$clients"
    echo "============================================================"

    cleanup_local_mvp

    ROLE=server \
    CLIENTS="$clients" \
    LOCAL_CLIENTS="$clients" \
    SERVER_COUNT="$SERVER_COUNT" \
    SERVER_START_ID="$SERVER_START_ID" \
    SERVER_AUTO_STOP=1 \
    bash "$ROOT_DIR/run_mvp_multimachine_benchmark.sh"

    echo "[CLEAN-SUITE] Finished MVP server group: clients=$clients"
    cleanup_local_mvp
    sleep "$GROUP_PAUSE_SECONDS"
done

echo "[CLEAN-SUITE] All MVP server groups finished."
