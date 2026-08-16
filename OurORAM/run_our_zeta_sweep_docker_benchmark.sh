#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT_DIR/benchmark_results/zeta_sweep"
LOG_DIR="$ROOT_DIR/benchmark_logs/zeta_sweep"
mkdir -p "$OUT_DIR" "$LOG_DIR"

BANDWIDTH="${BANDWIDTH:-10gbit}"
BANDWIDTH_LABEL="${BANDWIDTH_LABEL:-$(echo "$BANDWIDTH" | tr -c '[:alnum:]_-' '_')}"

N_CLIENTS="${N_CLIENTS:-50}"
N_REQUESTS="${N_REQUESTS:-256}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"

ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-1}"
BUCKET_SIZE_VALUES="${BUCKET_SIZE_VALUES:-2 3 4}"
COMPETITION_BUCKET_SIZE_VALUES="${COMPETITION_BUCKET_SIZE_VALUES:-1 2 3 4}"

RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-28800}"
BUILD_PROJECT="${BUILD_PROJECT:-0}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"

RESULT_CSV="${RESULT_CSV:-$OUT_DIR/OurORAM_zeta_sweep_${BANDWIDTH_LABEL}_c${N_CLIENTS}_N${BID_EXPONENT}_B${BLOCK_SIZE}.csv}"
SETTING_CSV="${SETTING_CSV:-$OUT_DIR/OurORAM_zeta_sweep_${BANDWIDTH_LABEL}_settings.csv}"
APPEND_RESULT="${APPEND_RESULT:-0}"

CSV_HEADER="scheme,clients,logN,blockSize,requestsPerClient,bandwidth,Z,zeta,rootBucketSize,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,round1BytesPerAccess,round2BytesPerAccess,round3BytesPerAccess,totalBytesPerAccess,round1MBPerAccess,round2MBPerAccess,round3MBPerAccess,totalMBPerAccess,status,tag"
SETTING_HEADER="tag,scheme,clients,logN,blockSize,requestsPerClient,bandwidth,Z,zeta,rootBucketSize,timeoutSeconds,buildProject,buildImage,javaHeapOpts,rootDir"

usage() {
    cat <<EOF
Usage:
  ./run_our_zeta_sweep_docker_benchmark.sh

Default sweep:
  clients=$N_CLIENTS, Z in "$BUCKET_SIZE_VALUES", zeta in "$COMPETITION_BUCKET_SIZE_VALUES"

Environment variables:
  BANDWIDTH                         Docker tc bandwidth. Default: $BANDWIDTH
  N_CLIENTS                         Concurrent clients. Default: $N_CLIENTS
  N_REQUESTS                        Requests per client. Default: $N_REQUESTS
  BID_EXPONENT                      log2(N). Default: $BID_EXPONENT
  BLOCK_SIZE                        Block size. Default: $BLOCK_SIZE
  ROOT_BUCKET_SIZE                  Root bucket base size. Default: $ROOT_BUCKET_SIZE
  BUCKET_SIZE_VALUES                Non-competition bucket Z values. Default: "$BUCKET_SIZE_VALUES"
  COMPETITION_BUCKET_SIZE_VALUES    Competition bucket zeta values. Default: "$COMPETITION_BUCKET_SIZE_VALUES"
  BUILD_PROJECT                     Run ./gradlew installDist when 1. Default: $BUILD_PROJECT
  BUILD_IMAGE                       Rebuild Docker image on first run when 1. Default: $BUILD_IMAGE
  RUN_TIMEOUT_SECONDS               Timeout per setting. Default: $RUN_TIMEOUT_SECONDS
  JAVA_HEAP_OPTS                    JVM heap opts. Default: $JAVA_HEAP_OPTS
  APPEND_RESULT                     Append to existing CSV when 1. Default: $APPEND_RESULT

Outputs:
  $RESULT_CSV
  $SETTING_CSV
  $LOG_DIR

Example:
  BUILD_PROJECT=1 BUILD_IMAGE=1 N_CLIENTS=50 N_REQUESTS=256 \\
  BUCKET_SIZE_VALUES="2 3 4" COMPETITION_BUCKET_SIZE_VALUES="1 2 3 4" \\
  BANDWIDTH=10gbit ./run_our_zeta_sweep_docker_benchmark.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

run_gradle() {
    if command -v gradle >/dev/null 2>&1; then
        gradle "$@"
    else
        ./gradlew "$@"
    fi
}

extract_metric() {
    local pattern="$1"
    local field="$2"
    local file="$3"
    grep -oE "$pattern" "$file" 2>/dev/null | tail -n 1 | awk -v f="$field" '{print $f}' || true
}

bytes_to_mb() {
    local bytes="$1"
    if [[ -z "$bytes" || "$bytes" == "NA" ]]; then
        printf "NA"
    else
        awk -v b="$bytes" 'BEGIN { printf "%.6f", b / 1000000.0 }'
    fi
}

write_headers() {
    if [[ "$APPEND_RESULT" != "1" || ! -f "$RESULT_CSV" ]]; then
        echo "$CSV_HEADER" > "$RESULT_CSV"
    fi
    if [[ "$APPEND_RESULT" != "1" || ! -f "$SETTING_CSV" ]]; then
        echo "$SETTING_HEADER" > "$SETTING_CSV"
    fi
}

build_project_if_needed() {
    if [[ "$BUILD_PROJECT" != "1" ]]; then
        return
    fi
    echo "[INFO] Building OurMultiServer"
    (cd "$ROOT_DIR" && run_gradle installDist)
}

append_failed_result() {
    local z="$1"
    local zeta="$2"
    local status="$3"
    local tag="$4"
    echo "C2ORAM,$N_CLIENTS,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,$zeta,$ROOT_BUCKET_SIZE,NA,NA,NA,NA,NA,NA,NA,NA,NA,NA,NA,NA,$status,$tag" >> "$RESULT_CSV"
}

run_one() {
    local z="$1"
    local zeta="$2"
    local tag="OurORAM_c${N_CLIENTS}_N${BID_EXPONENT}_B${BLOCK_SIZE}_Z${z}_zeta${zeta}_${BANDWIDTH_LABEL}"
    local run_log_dir="$LOG_DIR/$tag"
    local scheme_log_dir="$ROOT_DIR/benchmark_logs/docker_bandwidth"
    local client_log="$scheme_log_dir/client.log"
    local server_log="$scheme_log_dir/server.log"
    local iperf_log="$scheme_log_dir/iperf.log"
    local build_image_this_run="$BUILD_IMAGE"

    mkdir -p "$run_log_dir"

    echo
    echo "============================================================"
    echo "[INFO] $tag"
    echo "[INFO] clients=$N_CLIENTS, requests=$N_REQUESTS, logN=$BID_EXPONENT, blockSize=$BLOCK_SIZE, Z=$z, zeta=$zeta"
    echo "============================================================"

    set +e
    (
        cd "$ROOT_DIR"
        timeout "$RUN_TIMEOUT_SECONDS" env \
            BANDWIDTH="$BANDWIDTH" \
            N_REQUESTS="$N_REQUESTS" \
            N_CLIENTS="$N_CLIENTS" \
            BID_EXPONENT="$BID_EXPONENT" \
            ROOT_BUCKET_SIZE="$ROOT_BUCKET_SIZE" \
            COMPETITION_BUCKET_SIZE="$zeta" \
            BUCKET_SIZE="$z" \
            BLOCK_SIZE="$BLOCK_SIZE" \
            BUILD_IMAGE="$build_image_this_run" \
            JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
            ./run_our_docker_bandwidth_benchmark.sh
    )
    local rc=$?
    set -e

    if [[ -f "$client_log" ]]; then
        cp "$client_log" "$run_log_dir/client.log"
    fi
    if [[ -f "$server_log" ]]; then
        cp "$server_log" "$run_log_dir/server.log"
    fi
    if [[ -f "$iperf_log" ]]; then
        cp "$iperf_log" "$run_log_dir/iperf.log"
    fi

    echo "$tag,C2ORAM,$N_CLIENTS,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,$zeta,$ROOT_BUCKET_SIZE,$RUN_TIMEOUT_SECONDS,$BUILD_PROJECT,$BUILD_IMAGE,$JAVA_HEAP_OPTS,$ROOT_DIR" >> "$SETTING_CSV"

    if [[ "$rc" -eq 124 ]]; then
        echo "[WARN] Timeout after ${RUN_TIMEOUT_SECONDS}s: $tag"
        append_failed_result "$z" "$zeta" "TIMEOUT" "$tag"
        return
    elif [[ "$rc" -ne 0 ]]; then
        echo "[WARN] Run failed with rc=$rc: $tag"
        append_failed_result "$z" "$zeta" "FAILED" "$tag"
        return
    fi

    local total_ops time_sec throughput latency_ms
    local round1_bytes round2_bytes round3_bytes total_bytes
    local round1_mb round2_mb round3_mb total_mb

    total_ops="$(extract_metric 'Measured ops\[#\]: [0-9]+' 3 "$client_log")"
    time_sec="$(extract_metric 'Wall-clock time\[s\]: [0-9.]+' 3 "$client_log")"
    throughput="$(extract_metric 'Throughput\[ops/s\]: [0-9.]+' 2 "$client_log")"
    round1_bytes="$(extract_metric 'Avg Round1 bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"
    round2_bytes="$(extract_metric 'Avg Round2 bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"
    round3_bytes="$(extract_metric 'Avg Round3 bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"
    total_bytes="$(extract_metric 'Avg Total bandwidth per access\[bytes\]: [0-9.]+' 6 "$client_log")"

    total_ops="${total_ops:-NA}"
    time_sec="${time_sec:-NA}"
    throughput="${throughput:-NA}"
    round1_bytes="${round1_bytes:-NA}"
    round2_bytes="${round2_bytes:-NA}"
    round3_bytes="${round3_bytes:-NA}"
    total_bytes="${total_bytes:-NA}"

    if [[ "$time_sec" == "NA" ]]; then
        latency_ms="NA"
    else
        latency_ms="$(awk -v t="$time_sec" -v r="$N_REQUESTS" 'BEGIN { printf "%.6f", (t / r) * 1000.0 }')"
    fi

    round1_mb="$(bytes_to_mb "$round1_bytes")"
    round2_mb="$(bytes_to_mb "$round2_bytes")"
    round3_mb="$(bytes_to_mb "$round3_bytes")"
    total_mb="$(bytes_to_mb "$total_bytes")"

    if [[ "$total_ops" == "NA" || "$time_sec" == "NA" || "$throughput" == "NA" ]]; then
        echo "[WARN] Could not parse benchmark output: $tag"
        append_failed_result "$z" "$zeta" "PARSE_FAILED" "$tag"
        return
    fi

    echo "C2ORAM,$N_CLIENTS,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$z,$zeta,$ROOT_BUCKET_SIZE,$total_ops,$time_sec,$throughput,$latency_ms,$round1_bytes,$round2_bytes,$round3_bytes,$total_bytes,$round1_mb,$round2_mb,$round3_mb,$total_mb,OK,$tag" >> "$RESULT_CSV"

    echo "[RESULT] Z=$z zeta=$zeta throughput=$throughput ops/s latencyMs=$latency_ms totalMB/access=$total_mb"
}

main() {
    write_headers
    build_project_if_needed

    local first_run=1
    for z in $BUCKET_SIZE_VALUES; do
        for zeta in $COMPETITION_BUCKET_SIZE_VALUES; do
            if [[ "$first_run" == "1" ]]; then
                run_one "$z" "$zeta"
                first_run=0
            else
                BUILD_IMAGE=0 run_one "$z" "$zeta"
            fi
        done
    done

    echo
    echo "[INFO] Result CSV: $RESULT_CSV"
    echo "[INFO] Setting CSV: $SETTING_CSV"
    echo "[INFO] Logs: $LOG_DIR"
}

main
