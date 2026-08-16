#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT_DIR/benchmark_results/parameter_sweep"
LOG_DIR="$ROOT_DIR/benchmark_logs/parameter_sweep"
mkdir -p "$OUT_DIR" "$LOG_DIR"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
Z_VALUES="${Z_VALUES:-2 3 4}"
N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
ZIPF_PARAMETER="${ZIPF_PARAMETER:-1.0}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-7200}"
BUILD_PROJECT="${BUILD_PROJECT:-0}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
IMAGE_NAME="${IMAGE_NAME:-mvporam-z-sweep}"
REPEATS="${REPEATS:-1}"
RUN_PAUSE_SECONDS="${RUN_PAUSE_SECONDS:-0}"
RUN_LABEL="${RUN_LABEL:-}"

BANDWIDTH_LABEL="$(printf '%s' "$BANDWIDTH" | tr -c '[:alnum:]_-' '_')"
RESULT_CSV="${RESULT_CSV:-$OUT_DIR/MVPORAM_Z_sweep_${BANDWIDTH_LABEL}_N${BID_EXPONENT}_B${BLOCK_SIZE}.csv}"
CSV_HEADER="scheme,clients,logN,blockSize,requestsPerClient,bandwidth,Z,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag"

usage() {
    cat <<EOF
Usage:
  bash ./run_mvp_z_sweep_docker_benchmark.sh

Default sweep:
  clients: $CLIENT_VALUES
  Z:       $Z_VALUES

Defaults:
  requests/client=$N_REQUESTS, logN=$BID_EXPONENT, blockSize=$BLOCK_SIZE,
  zipf=$ZIPF_PARAMETER, bandwidth=$BANDWIDTH

Existing OK settings are skipped. Failed and timed-out settings are retried.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

extract_metric() {
    local pattern="$1" field="$2" file="$3"
    grep -oE "$pattern" "$file" 2>/dev/null | tail -n 1 | awk -v f="$field" '{print $f}' || true
}

already_completed() {
    local tag="$1"
    [[ -f "$RESULT_CSV" ]] && awk -F, -v tag="$tag" \
        'NR > 1 && $12 == "OK" && $13 == tag { found=1 } END { exit !found }' "$RESULT_CSV"
}

remove_previous_row() {
    local tag="$1" tmp="$RESULT_CSV.tmp"
    awk -F, -v tag="$tag" 'NR == 1 || $13 != tag' "$RESULT_CSV" > "$tmp"
    mv "$tmp" "$RESULT_CSV"
}

append_failed() {
    local clients="$1" z="$2" status="$3" tag="$4"
    echo "MVPORAM,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,NA,NA,NA,NA,$status,$tag" >> "$RESULT_CSV"
}

run_one() {
    local clients="$1" z="$2" repeat="$3" build_image="$4"
    local tag="MVPORAM_c${clients}_N${BID_EXPONENT}_B${BLOCK_SIZE}_Z${z}_${BANDWIDTH_LABEL}"
    [[ -n "$RUN_LABEL" ]] && tag="${tag}_${RUN_LABEL}"
    (( REPEATS > 1 )) && tag="${tag}_repeat${repeat}"
    local run_log_dir="$LOG_DIR/$tag"
    local source_log_dir="$ROOT_DIR/benchmark_logs/docker_bandwidth"
    local client_log="$source_log_dir/client.log"
    local server_log="$source_log_dir/server.log"
    local iperf_log="$source_log_dir/iperf.log"

    if already_completed "$tag"; then
        echo "[SKIP] $tag is already OK"
        return 0
    fi
    remove_previous_row "$tag"
    mkdir -p "$run_log_dir"

    echo
    echo "============================================================"
    echo "[SWEEP] $tag"
    echo "[SWEEP] clients=$clients Z=$z"
    echo "============================================================"

    docker rm -f mvporam-client mvporam-server >/dev/null 2>&1 || true

    set +e
    (
        cd "$ROOT_DIR"
        timeout "$RUN_TIMEOUT_SECONDS" env \
            IMAGE_NAME="$IMAGE_NAME" \
            BUILD_IMAGE="$build_image" \
            BANDWIDTH="$BANDWIDTH" \
            N_REQUESTS="$N_REQUESTS" \
            N_CLIENTS="$clients" \
            BID_EXPONENT="$BID_EXPONENT" \
            BUCKET_SIZE="$z" \
            BLOCK_SIZE="$BLOCK_SIZE" \
            ZIPF_PARAMETER="$ZIPF_PARAMETER" \
            JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
            bash ./run_mvp_docker_bandwidth_benchmark.sh
    )
    local rc=$?
    set -e

    [[ -f "$client_log" ]] && cp "$client_log" "$run_log_dir/client.log"
    [[ -f "$server_log" ]] && cp "$server_log" "$run_log_dir/server.log"
    [[ -f "$iperf_log" ]] && cp "$iperf_log" "$run_log_dir/iperf.log"

    if [[ "$rc" -eq 124 ]]; then
        append_failed "$clients" "$z" TIMEOUT "$tag"
        echo "[WARN] Timed out: $tag"
        return 0
    elif [[ "$rc" -ne 0 ]]; then
        append_failed "$clients" "$z" FAILED "$tag"
        echo "[WARN] Failed with rc=$rc: $tag"
        return 0
    fi

    local total_ops time_sec throughput latency_ms
    total_ops="$(extract_metric 'Measured ops\[#\]: [0-9]+' 3 "$client_log")"
    time_sec="$(extract_metric 'Wall-clock time\[s\]: [0-9.]+' 3 "$client_log")"
    throughput="$(extract_metric 'Throughput\[ops/s\]: [0-9.]+' 2 "$client_log")"

    if [[ -z "$total_ops" || -z "$time_sec" || -z "$throughput" ]]; then
        append_failed "$clients" "$z" PARSE_FAILED "$tag"
        echo "[WARN] Could not parse result: $tag"
        return 0
    fi

    latency_ms="$(awk -v t="$time_sec" -v r="$N_REQUESTS" 'BEGIN { printf "%.6f", t * 1000.0 / r }')"
    echo "MVPORAM,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,$total_ops,$time_sec,$throughput,$latency_ms,OK,$tag" >> "$RESULT_CSV"
    echo "[RESULT] clients=$clients Z=$z throughput=$throughput latencyMs=$latency_ms"
}

main() {
    if ! [[ "$REPEATS" =~ ^[1-9][0-9]*$ ]]; then
        echo "[ERROR] REPEATS must be a positive integer"
        exit 1
    fi
    [[ -f "$RESULT_CSV" ]] || echo "$CSV_HEADER" > "$RESULT_CSV"

    if [[ "$BUILD_PROJECT" == "1" ]]; then
        echo "[INFO] Building MVPORAM"
        (cd "$ROOT_DIR" && ./gradlew installDist)
    fi

    local first_run=1
    for z in $Z_VALUES; do
        for clients in $CLIENT_VALUES; do
            for ((repeat = 1; repeat <= REPEATS; repeat++)); do
                if [[ "$first_run" == "1" ]]; then
                    run_one "$clients" "$z" "$repeat" "$BUILD_IMAGE"
                    first_run=0
                else
                    run_one "$clients" "$z" "$repeat" 0
                fi
                (( RUN_PAUSE_SECONDS > 0 )) && sleep "$RUN_PAUSE_SECONDS"
            done
        done
    done

    echo
    echo "[INFO] Sweep complete"
    echo "[INFO] Results: $RESULT_CSV"
    echo "[INFO] Logs: $LOG_DIR"
}

main
