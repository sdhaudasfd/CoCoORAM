#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUR_DIR="$ROOT_DIR"
OUT_DIR="$ROOT_DIR/results"

BANDWIDTH="${BANDWIDTH:-10gbit}"
BANDWIDTH_LABEL="${BANDWIDTH_LABEL:-${BANDWIDTH//[^A-Za-z0-9_-]/_}}"
LOG_DIR="$OUT_DIR/latency_logs_${BANDWIDTH_LABEL}"
mkdir -p "$LOG_DIR"

N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 20}"
BLOCK_SIZE_VALUES="${BLOCK_SIZE_VALUES:-64 256 1024 4096}"
BUILD_PROJECTS="${BUILD_PROJECTS:-0}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"

ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-1}"
COMPETITION_BUCKET_SIZE="${COMPETITION_BUCKET_SIZE:-1}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-28800}"

RESULT_CSV="$OUT_DIR/C2ORAM_latency_breakdown_${BANDWIDTH_LABEL}.csv"
SETTING_CSV="$OUT_DIR/C2ORAM_latency_breakdown_${BANDWIDTH_LABEL}_settings.csv"

usage() {
    cat <<EOF
Usage:
  BANDWIDTH=10gbit ./run_our_latency_breakdown.sh

Purpose:
  Re-run only C2ORAM/OurORAM for the access latency breakdown figure.

Optional environment variables:
  BANDWIDTH, BANDWIDTH_LABEL
  N_REQUESTS, BID_EXPONENT, CLIENT_VALUES, BLOCK_SIZE_VALUES
  ROOT_BUCKET_SIZE, COMPETITION_BUCKET_SIZE, BUCKET_SIZE
  BUILD_PROJECTS, BUILD_IMAGE, RUN_TIMEOUT_SECONDS

Outputs:
  $RESULT_CSV
  $SETTING_CSV
  $LOG_DIR
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

if [[ "$BUILD_PROJECTS" == "1" ]]; then
    echo "[INFO] Building OurORAM"
    (
        cd "$OUR_DIR"
        ./gradlew installDist
    )
fi

cat > "$RESULT_CSV" <<EOF
scheme,clients,logN,blockSize,requestsPerClient,bandwidth,totalOps,totalTimeSec,throughputOpsPerSec,avgTotalMs,round1Ms,round2Ms,round3Ms,status,tag
EOF

cat > "$SETTING_CSV" <<EOF
tag,scheme,clients,logN,blockSize,requestsPerClient,bandwidth,rootBucketSize,competitionBucketSize,bucketSize,timeoutSeconds,buildProjects,buildImage,rootDir
EOF

image_built=0
for block_size in $BLOCK_SIZE_VALUES; do
    for clients in $CLIENT_VALUES; do
        tag="C2ORAM_latency_c${clients}_B${block_size}_${BANDWIDTH_LABEL}"
        status="OK"

        echo
        echo "============================================================"
        echo "[INFO] $tag"
        echo "[INFO] bandwidth=$BANDWIDTH, requests=$N_REQUESTS, logN=$BID_EXPONENT, clients=$clients, blockSize=$block_size"
        echo "============================================================"

        run_build_image="0"
        if [[ "$BUILD_IMAGE" == "1" && "$image_built" == "0" ]]; then
            run_build_image="1"
            image_built=1
        fi

        set +e
        (
            cd "$OUR_DIR"
            timeout "$RUN_TIMEOUT_SECONDS" env \
                BANDWIDTH="$BANDWIDTH" \
                N_REQUESTS="$N_REQUESTS" \
                N_CLIENTS="$clients" \
                BID_EXPONENT="$BID_EXPONENT" \
                ROOT_BUCKET_SIZE="$ROOT_BUCKET_SIZE" \
                COMPETITION_BUCKET_SIZE="$COMPETITION_BUCKET_SIZE" \
                BUCKET_SIZE="$BUCKET_SIZE" \
                BLOCK_SIZE="$block_size" \
                BUILD_IMAGE="$run_build_image" \
                bash ./run_our_docker_bandwidth_benchmark.sh
        )
        exit_code=$?
        set -e

        if [[ "$exit_code" -eq 124 ]]; then
            status="TIMEOUT"
        elif [[ "$exit_code" -ne 0 ]]; then
            status="FAILED"
        fi

        client_log="$OUR_DIR/benchmark_logs/docker_bandwidth/client.log"
        server_log="$OUR_DIR/benchmark_logs/docker_bandwidth/server.log"
        iperf_log="$OUR_DIR/benchmark_logs/docker_bandwidth/iperf.log"

        [[ -f "$client_log" ]] && cp "$client_log" "$LOG_DIR/${tag}_client.log"
        [[ -f "$server_log" ]] && cp "$server_log" "$LOG_DIR/${tag}_server.log"
        [[ -f "$iperf_log" ]] && cp "$iperf_log" "$LOG_DIR/${tag}_iperf.log"

        total_ops="NA"
        wall_time="NA"
        throughput="NA"
        avg_total_ms="NA"
        round1_ms="NA"
        round2_ms="NA"
        round3_ms="NA"

        if [[ -f "$client_log" ]]; then
            total_ops="$(extract_metric 'Measured ops\[#\]: [0-9]+' 3 "$client_log")"
            wall_time="$(extract_metric 'Wall-clock time\[s\]: [0-9.]+' 3 "$client_log")"
            throughput="$(extract_metric 'Throughput\[ops/s\]: [0-9.]+' 2 "$client_log")"
            avg_total_ms="$(extract_metric 'avgTotalMs=[0-9.]+' 1 "$client_log" | cut -d= -f2)"
            round1_ms="$(extract_metric 'avgRound1PhaseMs=[0-9.]+' 1 "$client_log" | cut -d= -f2)"
            round2_ms="$(extract_metric 'avgRound2PhaseMs=[0-9.]+' 1 "$client_log" | cut -d= -f2)"
            round3_ms="$(extract_metric 'avgRound3PhaseMs=[0-9.]+' 1 "$client_log" | cut -d= -f2)"
        fi

        total_ops="${total_ops:-NA}"
        wall_time="${wall_time:-NA}"
        throughput="${throughput:-NA}"
        avg_total_ms="${avg_total_ms:-NA}"
        round1_ms="${round1_ms:-NA}"
        round2_ms="${round2_ms:-NA}"
        round3_ms="${round3_ms:-NA}"

        echo "C2ORAM,$clients,$BID_EXPONENT,$block_size,$N_REQUESTS,$BANDWIDTH,$total_ops,$wall_time,$throughput,$avg_total_ms,$round1_ms,$round2_ms,$round3_ms,$status,$tag" >> "$RESULT_CSV"
        echo "$tag,C2ORAM,$clients,$BID_EXPONENT,$block_size,$N_REQUESTS,$BANDWIDTH,$ROOT_BUCKET_SIZE,$COMPETITION_BUCKET_SIZE,$BUCKET_SIZE,$RUN_TIMEOUT_SECONDS,$BUILD_PROJECTS,$BUILD_IMAGE,$ROOT_DIR" >> "$SETTING_CSV"

        echo "[RESULT] clients=$clients blockSize=$block_size status=$status totalMs=$avg_total_ms R1=$round1_ms R2=$round2_ms R3=$round3_ms"
    done
done

echo
echo "[INFO] Done."
echo "[INFO] Results: $RESULT_CSV"
echo "[INFO] Settings: $SETTING_CSV"
echo "[INFO] Logs: $LOG_DIR"

python3 "$ROOT_DIR/plot_latency_breakdown.py" "$RESULT_CSV" \
    --output "$OUT_DIR/access_latency_breakdown.pdf"
