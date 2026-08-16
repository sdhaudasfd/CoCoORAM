#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT_DIR/benchmark_results/parameter_sweep"
LOG_DIR="$ROOT_DIR/benchmark_logs/parameter_sweep"
mkdir -p "$OUT_DIR" "$LOG_DIR"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
Z_VALUES="${Z_VALUES:-2 3 4}"
ZETA_VALUES="${ZETA_VALUES:-1 2 3 4}"

N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-28800}"
BUILD_PROJECT="${BUILD_PROJECT:-0}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
IMAGE_NAME="${IMAGE_NAME:-ouroram-parameter-sweep}"
REPEATS="${REPEATS:-1}"
RUN_PAUSE_SECONDS="${RUN_PAUSE_SECONDS:-0}"
RUN_LABEL="${RUN_LABEL:-}"

BANDWIDTH_LABEL="$(printf '%s' "$BANDWIDTH" | tr -c '[:alnum:]_-' '_')"
RESULT_CSV="${RESULT_CSV:-$OUT_DIR/OurORAM_parameter_sweep_root_equals_zeta_${BANDWIDTH_LABEL}_N${BID_EXPONENT}_B${BLOCK_SIZE}.csv}"
CSV_HEADER="scheme,clients,logN,blockSize,requestsPerClient,bandwidth,Z,zeta,rootBucketSize,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,round1BytesPerAccess,round2BytesPerAccess,round3BytesPerAccess,totalBytesPerAccess,status,tag"

usage() {
    cat <<EOF
Usage:
  bash ./run_our_parameter_sweep_docker_benchmark.sh

Default sweep:
  clients: $CLIENT_VALUES
  Z:       $Z_VALUES
  zeta:    $ZETA_VALUES

Defaults:
  requests/client=$N_REQUESTS, logN=$BID_EXPONENT, blockSize=$BLOCK_SIZE,
  rootBucketSize=zeta (varies together), bandwidth=$BANDWIDTH

The script preserves its CSV and skips settings already recorded as OK.

Useful overrides:
  CLIENT_VALUES="1 5 10 15 20 30 40 50"
  Z_VALUES="2 3 4"
  ZETA_VALUES="1 2 3 4"
  BUILD_PROJECT=1 BUILD_IMAGE=1
  IMAGE_NAME=ouroram-parameter-sweep
  RUN_TIMEOUT_SECONDS=28800
  RESULT_CSV=/path/to/result.csv
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

extract_metric() {
    local pattern="$1"
    local field="$2"
    local file="$3"
    grep -oE "$pattern" "$file" 2>/dev/null | tail -n 1 | awk -v f="$field" '{print $f}' || true
}

already_completed() {
    local tag="$1"
    [[ -f "$RESULT_CSV" ]] && awk -F, -v tag="$tag" 'NR > 1 && $18 == "OK" && $19 == tag { found=1 } END { exit !found }' "$RESULT_CSV"
}

remove_previous_failed_row() {
    local tag="$1"
    local tmp="$RESULT_CSV.tmp"
    awk -F, -v tag="$tag" 'NR == 1 || $19 != tag' "$RESULT_CSV" > "$tmp"
    mv "$tmp" "$RESULT_CSV"
}

append_failed() {
    local clients="$1" z="$2" zeta="$3" status="$4" tag="$5"
    local root_bucket_size="$zeta"
    echo "C2ORAM,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,$zeta,$root_bucket_size,NA,NA,NA,NA,NA,NA,NA,NA,$status,$tag" >> "$RESULT_CSV"
}

run_one() {
    local clients="$1" z="$2" zeta="$3" repeat="$4" build_image="$5"
    local root_bucket_size="$zeta"
    local tag="OurORAM_c${clients}_N${BID_EXPONENT}_B${BLOCK_SIZE}_Z${z}_root${root_bucket_size}_zeta${zeta}_${BANDWIDTH_LABEL}"
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
    remove_previous_failed_row "$tag"
    mkdir -p "$run_log_dir"

    echo
    echo "============================================================"
    echo "[SWEEP] $tag"
    echo "[SWEEP] clients=$clients Z=$z root=$root_bucket_size zeta=$zeta"
    echo "============================================================"

    set +e
    (
        cd "$ROOT_DIR"
        timeout "$RUN_TIMEOUT_SECONDS" env \
            BANDWIDTH="$BANDWIDTH" \
            N_REQUESTS="$N_REQUESTS" \
            N_CLIENTS="$clients" \
            BID_EXPONENT="$BID_EXPONENT" \
            ROOT_BUCKET_SIZE="$root_bucket_size" \
            COMPETITION_BUCKET_SIZE="$zeta" \
            BUCKET_SIZE="$z" \
            BLOCK_SIZE="$BLOCK_SIZE" \
            BUILD_IMAGE="$build_image" \
            IMAGE_NAME="$IMAGE_NAME" \
            JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
            bash ./run_our_docker_bandwidth_benchmark.sh
    )
    local rc=$?
    set -e

    [[ -f "$client_log" ]] && cp "$client_log" "$run_log_dir/client.log"
    [[ -f "$server_log" ]] && cp "$server_log" "$run_log_dir/server.log"
    [[ -f "$iperf_log" ]] && cp "$iperf_log" "$run_log_dir/iperf.log"

    if [[ "$rc" -eq 124 ]]; then
        append_failed "$clients" "$z" "$zeta" "TIMEOUT" "$tag"
        echo "[WARN] Timed out: $tag"
        return 0
    elif [[ "$rc" -ne 0 ]]; then
        append_failed "$clients" "$z" "$zeta" "FAILED" "$tag"
        echo "[WARN] Failed with rc=$rc: $tag"
        return 0
    fi

    local total_ops time_sec throughput latency_ms
    local round1_bytes round2_bytes round3_bytes total_bytes
    total_ops="$(extract_metric 'Measured ops\[#\]: [0-9]+' 3 "$client_log")"
    time_sec="$(extract_metric 'Wall-clock time\[s\]: [0-9.]+' 3 "$client_log")"
    throughput="$(extract_metric 'Throughput\[ops/s\]: [0-9.]+' 2 "$client_log")"
    round1_bytes="$(extract_metric 'Avg Round1 bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"
    round2_bytes="$(extract_metric 'Avg Round2 bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"
    round3_bytes="$(extract_metric 'Avg Round3 bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"
    total_bytes="$(extract_metric 'Avg Total bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"

    if [[ -z "$total_ops" || -z "$time_sec" || -z "$throughput" ]]; then
        append_failed "$clients" "$z" "$zeta" "PARSE_FAILED" "$tag"
        echo "[WARN] Could not parse result: $tag"
        return 0
    fi

    round1_bytes="${round1_bytes:-NA}"
    round2_bytes="${round2_bytes:-NA}"
    round3_bytes="${round3_bytes:-NA}"
    total_bytes="${total_bytes:-NA}"
    latency_ms="$(awk -v t="$time_sec" -v r="$N_REQUESTS" 'BEGIN { printf "%.6f", (t / r) * 1000.0 }')"

    echo "C2ORAM,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,$zeta,$root_bucket_size,$total_ops,$time_sec,$throughput,$latency_ms,$round1_bytes,$round2_bytes,$round3_bytes,$total_bytes,OK,$tag" >> "$RESULT_CSV"
    echo "[RESULT] clients=$clients Z=$z root=$root_bucket_size zeta=$zeta throughput=$throughput latencyMs=$latency_ms"
}

main() {
    if ! [[ "$REPEATS" =~ ^[1-9][0-9]*$ ]]; then
        echo "[ERROR] REPEATS must be a positive integer"
        exit 1
    fi
    if [[ ! -f "$RESULT_CSV" ]]; then
        echo "$CSV_HEADER" > "$RESULT_CSV"
    fi

    if [[ "$BUILD_PROJECT" == "1" ]]; then
        echo "[INFO] Building OurORAM"
        (cd "$ROOT_DIR" && ./gradlew installDist)
    fi

    local first_run=1
    for z in $Z_VALUES; do
        for zeta in $ZETA_VALUES; do
            for clients in $CLIENT_VALUES; do
                for ((repeat = 1; repeat <= REPEATS; repeat++)); do
                    if [[ "$first_run" == "1" ]]; then
                        run_one "$clients" "$z" "$zeta" "$repeat" "$BUILD_IMAGE"
                        first_run=0
                    else
                        run_one "$clients" "$z" "$zeta" "$repeat" 0
                    fi
                    (( RUN_PAUSE_SECONDS > 0 )) && sleep "$RUN_PAUSE_SECONDS"
                done
            done
        done
    done

    echo
    echo "[INFO] Sweep complete"
    echo "[INFO] Results: $RESULT_CSV"
    echo "[INFO] Logs: $LOG_DIR"
}

main
