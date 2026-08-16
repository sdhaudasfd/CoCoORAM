#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
N_SEARCHES="${N_SEARCHES:-1000}"
BID_EXPONENT="${BID_EXPONENT:-17}"
ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-1}"
COMPETITION_BUCKET_SIZE="${COMPETITION_BUCKET_SIZE:-2}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
BLOCK_SIZE="${BLOCK_SIZE:-256}"
QUERY_MODE="${QUERY_MODE:-uniform}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms16g -Xmx256g}"
BUILD_IMAGE="${BUILD_IMAGE:-1}"
STOP_ON_FAILURE="${STOP_ON_FAILURE:-0}"

RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/benchmark_results/sse_docker}"
PARAM_TAG="R${ROOT_BUCKET_SIZE}_C${COMPETITION_BUCKET_SIZE}_Z${BUCKET_SIZE}"
RESULT_CSV="${RESULT_CSV:-${RESULT_DIR}/CoCoSSE_${QUERY_MODE}_${BANDWIDTH}_B${BLOCK_SIZE}_${PARAM_TAG}_results.csv}"
LOG_ROOT="${LOG_ROOT:-${ROOT_DIR}/benchmark_logs/sse_sweep/${PARAM_TAG}}"

mkdir -p "${RESULT_DIR}" "${LOG_ROOT}"

cat > "${RESULT_CSV}" <<'CSV'
scheme,clients,searchesPerClient,queryMode,logN,rootBucketSize,competitionBucketSize,bucketSize,blockSize,bandwidth,totalSearches,usefulChunkAccesses,paddingAccesses,wallClockSec,searchThroughputPerSec,chunkThroughputPerSec,avgSearchLatencyMs,avgProtocolBandwidthBytesPerAccess,status,tag
CSV

extract_metric() {
    local label="$1"
    local file="$2"
    local value
    value="$(grep -F "${label}" "${file}" | tail -n 1 | sed 's/^[[:space:]]*//' | cut -d: -f2- | xargs || true)"
    if [[ -z "${value}" ]]; then
        printf 'NA'
    else
        printf '%s' "${value}"
    fi
}

sanitize_tag_component() {
    printf '%s' "$1" | tr -c '[:alnum:]_-' '_'
}

first_group=1

for clients in ${CLIENT_VALUES}; do
    tag="CoCoSSE_c${clients}_q$(sanitize_tag_component "${QUERY_MODE}")_N${BID_EXPONENT}_B${BLOCK_SIZE}_${PARAM_TAG}_${BANDWIDTH}"
    group_dir="${LOG_ROOT}/c${clients}"
    summary_log="${group_dir}/summary.log"
    mkdir -p "${group_dir}"

    image_flag=0
    if [[ "${first_group}" == "1" ]]; then
        image_flag="${BUILD_IMAGE}"
        first_group=0
    fi

    echo
    echo "============================================================"
    echo "[SWEEP] ${tag}"
    echo "============================================================"

    status="OK"
    if ! BUILD_IMAGE="${image_flag}" \
        BANDWIDTH="${BANDWIDTH}" \
        N_CLIENTS="${clients}" \
        N_SEARCHES="${N_SEARCHES}" \
        BID_EXPONENT="${BID_EXPONENT}" \
        ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE}" \
        COMPETITION_BUCKET_SIZE="${COMPETITION_BUCKET_SIZE}" \
        BUCKET_SIZE="${BUCKET_SIZE}" \
        BLOCK_SIZE="${BLOCK_SIZE}" \
        QUERY_MODE="${QUERY_MODE}" \
        JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS}" \
        bash "${ROOT_DIR}/run_sse_docker_benchmark.sh" \
        2>&1 | tee "${summary_log}"; then
        status="FAILED"
    fi

    client_log="${ROOT_DIR}/benchmark_logs/sse_docker/client.log"
    server_log="${ROOT_DIR}/benchmark_logs/sse_docker/server.log"
    iperf_log="${ROOT_DIR}/benchmark_logs/sse_docker/iperf.log"

    [[ -f "${client_log}" ]] && cp "${client_log}" "${group_dir}/client.log"
    [[ -f "${server_log}" ]] && cp "${server_log}" "${group_dir}/server.log"
    [[ -f "${iperf_log}" ]] && cp "${iperf_log}" "${group_dir}/iperf.log"

    if [[ "${status}" == "OK" && -f "${client_log}" ]]; then
        total_searches="$(extract_metric 'Completed searches[#]' "${client_log}")"
        useful_chunks="$(extract_metric 'Useful chunk accesses[#]' "${client_log}")"
        padding_accesses="$(extract_metric 'Padding accesses[#]' "${client_log}")"
        wall_clock="$(extract_metric 'Wall-clock time[s]' "${client_log}")"
        search_throughput="$(extract_metric 'Search throughput[searches/s]' "${client_log}")"
        chunk_throughput="$(extract_metric 'Chunk throughput[chunks/s]' "${client_log}")"
        search_latency="$(extract_metric 'Average search latency[ms]' "${client_log}")"
        protocol_bandwidth="$(extract_metric 'Average protocol bandwidth/access[bytes]' "${client_log}")"
    else
        total_searches="NA"
        useful_chunks="NA"
        padding_accesses="NA"
        wall_clock="NA"
        search_throughput="NA"
        chunk_throughput="NA"
        search_latency="NA"
        protocol_bandwidth="NA"
    fi

    printf '%s\n' \
        "CoCo-SSE,${clients},${N_SEARCHES},${QUERY_MODE},${BID_EXPONENT},${ROOT_BUCKET_SIZE},${COMPETITION_BUCKET_SIZE},${BUCKET_SIZE},${BLOCK_SIZE},${BANDWIDTH},${total_searches},${useful_chunks},${padding_accesses},${wall_clock},${search_throughput},${chunk_throughput},${search_latency},${protocol_bandwidth},${status},${tag}" \
        >> "${RESULT_CSV}"

    echo "[SWEEP] status=${status}"
    echo "[SWEEP] CSV=${RESULT_CSV}"

    if [[ "${status}" != "OK" ]]; then
        echo "[ERROR] Group failed for clients=${clients}; see ${summary_log}" >&2
        if [[ "${STOP_ON_FAILURE}" == "1" ]]; then
            exit 1
        fi
        echo "[WARN] Continuing with the next client group."
    fi
done

echo
echo "[DONE] All SSE groups completed."
echo "[DONE] Results: ${RESULT_CSV}"
column -s, -t "${RESULT_CSV}" 2>/dev/null || cat "${RESULT_CSV}"
