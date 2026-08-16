#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/benchmark_results}"
RESULT_CSV="${RESULT_CSV:-${RESULT_DIR}/BlockSSE_10gbit_results.csv}"
BUILD_PROJECT="${BUILD_PROJECT:-1}"
BUILD_IMAGE="${BUILD_IMAGE:-1}"

mkdir -p "${RESULT_DIR}"
if [[ "${APPEND_RESULTS:-0}" != "1" || ! -f "${RESULT_CSV}" ]]; then
    echo "scheme,clients,logN,blockSize,searchesPerClient,bandwidth,totalSearches,totalChunks,totalTimeSec,throughputSearchesPerSec,latencyMs,queueWaitMs,bytesPerSearch,bytesPerChunk,status,tag" >"${RESULT_CSV}"
fi

first=1
for clients in ${CLIENT_VALUES}; do
    log="${ROOT_DIR}/benchmark_logs/blocksse_suite/c${clients}.log"
    mkdir -p "$(dirname "${log}")"
    project_flag=0
    image_flag=0
    if [[ "${first}" == "1" ]]; then
        project_flag="${BUILD_PROJECT}"
        image_flag="${BUILD_IMAGE}"
        first=0
    fi

    set +e
    BUILD_PROJECT="${project_flag}" \
    BUILD_IMAGE="${image_flag}" \
    VERIFY_BANDWIDTH=0 \
    N_CLIENTS="${clients}" \
    bash "${ROOT_DIR}/run_blocksse_docker_benchmark.sh" >"${log}" 2>&1
    rc=$?
    set -e

    searches="$(sed -n 's/.*Completed searches\[#\]:[[:space:]]*\([0-9]*\).*/\1/p' "${log}" | tail -1)"
    chunks="$(sed -n 's/.*Useful chunk accesses\[#\]:[[:space:]]*\([0-9]*\).*/\1/p' "${log}" | tail -1)"
    wall="$(sed -n 's/.*Wall-clock time\[s\]:[[:space:]]*\([0-9.]*\).*/\1/p' "${log}" | tail -1)"
    throughput="$(sed -n 's/.*Search throughput\[searches\/s\]:[[:space:]]*\([0-9.]*\).*/\1/p' "${log}" | tail -1)"
    latency="$(sed -n 's/.*Average search latency\[ms\]:[[:space:]]*\([0-9.]*\).*/\1/p' "${log}" | tail -1)"
    queue="$(sed -n 's/.*Average queue wait\[ms\]:[[:space:]]*\([0-9.]*\).*/\1/p' "${log}" | tail -1)"
    bytes_search="$(sed -n 's/.*Average protocol bandwidth\/search\[bytes\]:[[:space:]]*\([0-9.]*\).*/\1/p' "${log}" | tail -1)"
    bytes_chunk="$(sed -n 's/.*Average protocol bandwidth\/chunk\[bytes\]:[[:space:]]*\([0-9.]*\).*/\1/p' "${log}" | tail -1)"
    tag="BlockSSE_c${clients}_N${BID_EXPONENT:-17}_B${BLOCK_SIZE:-256}_${BANDWIDTH:-10gbit}"

    if [[ "${rc}" -eq 0 && -n "${throughput}" ]]; then
        echo "BlockSSE,${clients},${BID_EXPONENT:-17},${BLOCK_SIZE:-256},${N_SEARCHES:-1000},${BANDWIDTH:-10gbit},${searches},${chunks},${wall},${throughput},${latency},${queue},${bytes_search},${bytes_chunk},OK,${tag}" >>"${RESULT_CSV}"
    else
        echo "BlockSSE,${clients},${BID_EXPONENT:-17},${BLOCK_SIZE:-256},${N_SEARCHES:-1000},${BANDWIDTH:-10gbit},NA,NA,NA,NA,NA,NA,NA,NA,FAILED,${tag}" >>"${RESULT_CSV}"
        cat "${log}" >&2
        exit "${rc}"
    fi
    echo "[SUITE] clients=${clients} complete"
done

echo "[RESULT] ${RESULT_CSV}"
