#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLIENT_VALUES=(${CLIENT_VALUES:-1 5 10 15 20 30 40 50})
CONCURRENCY_VALUES=(${CONCURRENCY_VALUES:-1 5 10 10 10 10 10 10})
RESULT_CSV="${RESULT_CSV:-$ROOT_DIR/benchmark_results/MVPORAM_admission_sweep.csv}"
BUILD_IMAGE_FIRST="${BUILD_IMAGE:-1}"
RUN_LABEL="${RUN_LABEL:-}"
REPEATS="${REPEATS:-1}"
RUN_PAUSE_SECONDS="${RUN_PAUSE_SECONDS:-0}"

if ! [[ "$REPEATS" =~ ^[1-9][0-9]*$ ]]; then
    echo "[ERROR] REPEATS must be a positive integer"
    exit 1
fi

if (( ${#CLIENT_VALUES[@]} != ${#CONCURRENCY_VALUES[@]} )); then
    echo "[ERROR] CLIENT_VALUES and CONCURRENCY_VALUES must have equal lengths"
    exit 1
fi

mkdir -p "$(dirname "$RESULT_CSV")"
echo "scheme,clients,maxConcurrentClients,logN,blockSize,requestsPerClient,bandwidth,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag" > "$RESULT_CSV"

for ((i = 0; i < ${#CLIENT_VALUES[@]}; i++)); do
    clients="${CLIENT_VALUES[$i]}"
    concurrency="${CONCURRENCY_VALUES[$i]}"
    for ((repeat = 1; repeat <= REPEATS; repeat++)); do
        tag="MVPORAM_fixed_c${clients}_active${concurrency}_N${BID_EXPONENT:-18}_B${BLOCK_SIZE:-4096}_${BANDWIDTH:-10gbit}"
        if [[ -n "$RUN_LABEL" ]]; then
            tag="${tag}_${RUN_LABEL}"
        fi
        if (( REPEATS > 1 )); then
            tag="${tag}_repeat${repeat}"
        fi
        echo "[SWEEP] $tag"

        build_image=0
        if (( i == 0 && repeat == 1 )); then
            build_image="$BUILD_IMAGE_FIRST"
        fi

        if BUILD_IMAGE="$build_image" N_CLIENTS="$clients" MAX_CONCURRENT_CLIENTS="$concurrency" \
            bash "$ROOT_DIR/run_mvp_docker_bandwidth_benchmark.sh"; then
            log="$ROOT_DIR/benchmark_logs/docker_bandwidth/client.log"
            total_ops="$(grep 'Measured ops\[#\]:' "$log" | tail -1 | awk -F: '{gsub(/[[:space:]]/,"",$2); print $2}')"
            total_time="$(grep 'Wall-clock time\[s\]:' "$log" | tail -1 | awk -F: '{gsub(/[[:space:]]/,"",$2); print $2}')"
            throughput="$(grep 'Throughput\[ops/s\]:' "$log" | tail -1 | awk -F: '{gsub(/[[:space:]]/,"",$2); print $2}')"
            latency="$(awk -v t="$total_time" -v r="${N_REQUESTS:-1000}" 'BEGIN { printf "%.6f", t * 1000.0 / r }')"
            status=OK
        else
            total_ops=NA; total_time=NA; throughput=NA; latency=NA; status=FAILED
        fi

        run_log_dir="$ROOT_DIR/benchmark_logs/admission_sweep/$tag"
        mkdir -p "$run_log_dir"
        cp "$ROOT_DIR/benchmark_logs/docker_bandwidth/"*.log "$run_log_dir/" 2>/dev/null || true

        echo "MVPORAM,$clients,$concurrency,${BID_EXPONENT:-18},${BLOCK_SIZE:-4096},${N_REQUESTS:-1000},${BANDWIDTH:-10gbit},$total_ops,$total_time,$throughput,$latency,$status,$tag" >> "$RESULT_CSV"

        if (( RUN_PAUSE_SECONDS > 0 )) && ! (( i == ${#CLIENT_VALUES[@]} - 1 && repeat == REPEATS )); then
            sleep "$RUN_PAUSE_SECONDS"
        fi
    done
done

echo "[RESULT] $RESULT_CSV"
