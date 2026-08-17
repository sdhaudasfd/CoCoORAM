#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLIENT_VALUES=(${CLIENT_VALUES:-1 5 10 15 20 30 40 50})
CONCURRENCY_VALUES=(${CONCURRENCY_VALUES:-1 5 10 15 10 10 10 10})
RESULT_CSV="${RESULT_CSV:-$ROOT_DIR/benchmark_results/CoCoORAM_admission_sweep.csv}"
BUILD_IMAGE_FIRST="${BUILD_IMAGE:-1}"
RUN_LABEL="${RUN_LABEL:-}"
REPEATS="${REPEATS:-1}"
RUN_PAUSE_SECONDS="${RUN_PAUSE_SECONDS:-5}"

if (( ${#CLIENT_VALUES[@]} != ${#CONCURRENCY_VALUES[@]} )); then
    echo "[ERROR] CLIENT_VALUES and CONCURRENCY_VALUES must have equal lengths"
    exit 1
fi
if (( REPEATS < 1 )); then
    echo "[ERROR] REPEATS must be positive"
    exit 1
fi

mkdir -p "$(dirname "$RESULT_CSV")"
echo "scheme,clients,maxConcurrentClients,logN,blockSize,requestsPerClient,bandwidth,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag" > "$RESULT_CSV"

image_built=0
total_runs=$((REPEATS * ${#CLIENT_VALUES[@]}))
completed_runs=0

reset_run_containers() {
    local client_container="${CLIENT_CONTAINER:-ouroram-client}"
    local server_container="${SERVER_CONTAINER:-ouroram-server}"
    docker rm -f "$client_container" "$server_container" >/dev/null 2>&1 || true

    for ((attempt = 1; attempt <= 30; attempt++)); do
        if ! docker container inspect "$client_container" >/dev/null 2>&1 \
            && ! docker container inspect "$server_container" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done

    echo "[ERROR] Previous benchmark containers were not fully removed"
    return 1
}

for ((i = 0; i < ${#CLIENT_VALUES[@]}; i++)); do
    for ((repeat = 1; repeat <= REPEATS; repeat++)); do
        clients="${CLIENT_VALUES[$i]}"
        concurrency="${CONCURRENCY_VALUES[$i]}"
        tag="CoCoORAM_fixed_c${clients}_active${concurrency}_N${BID_EXPONENT:-18}_B${BLOCK_SIZE:-4096}_${BANDWIDTH:-10gbit}"
        if [[ -n "$RUN_LABEL" ]]; then
            tag="${tag}_${RUN_LABEL}"
        fi
        if (( REPEATS > 1 )); then
            tag="${tag}_repeat${repeat}"
        fi
        echo "[SWEEP] $tag"

        echo "[SWEEP] Ensuring a clean container state"
        reset_run_containers

        build_image=0
        if (( image_built == 0 )); then
            build_image="$BUILD_IMAGE_FIRST"
            image_built=1
        fi

        if BUILD_IMAGE="$build_image" N_CLIENTS="$clients" MAX_CONCURRENT_CLIENTS="$concurrency" \
            ROUND_ROBIN_ADMISSION_GROUPS="${ROUND_ROBIN_ADMISSION_GROUPS:-0}" \
            bash "$ROOT_DIR/run_our_docker_bandwidth_benchmark.sh"; then
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

        echo "C2ORAM,$clients,$concurrency,${BID_EXPONENT:-18},${BLOCK_SIZE:-4096},${N_REQUESTS:-1000},${BANDWIDTH:-10gbit},$total_ops,$total_time,$throughput,$latency,$status,$tag" >> "$RESULT_CSV"

        reset_run_containers

        completed_runs=$((completed_runs + 1))
        if (( completed_runs < total_runs && RUN_PAUSE_SECONDS > 0 )); then
            echo "[SWEEP] Waiting ${RUN_PAUSE_SECONDS}s before the next fresh-container run"
            sleep "$RUN_PAUSE_SECONDS"
        fi
    done
done

echo "[RESULT] $RESULT_CSV"
