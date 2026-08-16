#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="${ROOT_DIR}/benchmark_logs/sse_docker"
mkdir -p "${LOG_DIR}"

IMAGE_NAME="${IMAGE_NAME:-oursse-nettest}"
NETWORK_NAME="${NETWORK_NAME:-oursse-net}"
SERVER_CONTAINER="${SERVER_CONTAINER:-oursse-server}"
CLIENT_CONTAINER="${CLIENT_CONTAINER:-oursse-client}"
BUILD_IMAGE="${BUILD_IMAGE:-1}"

N_CLIENTS="${N_CLIENTS:-1}"
N_SEARCHES="${N_SEARCHES:-100}"
BID_EXPONENT="${BID_EXPONENT:-17}"
ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-1}"
COMPETITION_BUCKET_SIZE="${COMPETITION_BUCKET_SIZE:-1}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
BLOCK_SIZE="${BLOCK_SIZE:-256}"
QUERY_MODE="${QUERY_MODE:-uniform}"
BASELINE="${BASELINE:-0}"
SERVER_PORT="${SERVER_PORT:-19077}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms16g -Xmx256g}"

BANDWIDTH="${BANDWIDTH:-10gbit}"
BURST="${BURST:-32mbit}"
TC_LATENCY="${TC_LATENCY:-400ms}"
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-2400}"

SERVER_LOG="${LOG_DIR}/server.log"
CLIENT_LOG="${LOG_DIR}/client.log"
IPERF_LOG="${LOG_DIR}/iperf.log"

cleanup() {
    docker rm -f "${CLIENT_CONTAINER}" "${SERVER_CONTAINER}" >/dev/null 2>&1 || true
}

find_distribution='DIST_DIR=$(find /app/install -mindepth 1 -maxdepth 1 -type d -print -quit); test -n "$DIST_DIR"; cd "$DIST_DIR"'
java_prefix='java -Djava.security.properties=./config/java.security -Dlogback.configurationFile=./config/logback.xml -cp "lib/*"'

trap cleanup EXIT INT TERM

if [[ "${BUILD_IMAGE}" == "1" ]] || ! docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
    if ! find "${ROOT_DIR}/build/install" -mindepth 2 -maxdepth 2 -type d -name lib -print -quit | grep -q .; then
        echo "[ERROR] No Gradle distribution under ${ROOT_DIR}/build/install."
        echo "[ERROR] Run ./gradlew installDist first."
        exit 1
    fi
    echo "[INFO] Building Docker image: ${IMAGE_NAME}"
    docker build -f "${ROOT_DIR}/Dockerfile.sse" -t "${IMAGE_NAME}" "${ROOT_DIR}"
fi

if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
    docker network create "${NETWORK_NAME}" >/dev/null
fi

cleanup

echo "[INFO] Starting SSE server: clients=${N_CLIENTS}, buckets=${ROOT_BUCKET_SIZE}/${COMPETITION_BUCKET_SIZE}/${BUCKET_SIZE}, blockSize=${BLOCK_SIZE}"
docker run -d \
    --name "${SERVER_CONTAINER}" \
    --network "${NETWORK_NAME}" \
    --cap-add NET_ADMIN \
    -e JAVA_TOOL_OPTIONS="${JAVA_HEAP_OPTS}" \
    "${IMAGE_NAME}" \
    bash -lc "${find_distribution}; \
      tc qdisc add dev eth0 root tbf rate ${BANDWIDTH} burst ${BURST} latency ${TC_LATENCY}; \
      ${java_prefix} oram.server.ORAMServer \
      ${N_CLIENTS} ${BID_EXPONENT} ${ROOT_BUCKET_SIZE} ${COMPETITION_BUCKET_SIZE} \
      ${BUCKET_SIZE} ${BLOCK_SIZE} 0.0.0.0 ${SERVER_PORT} /app/DataSet/enron_20000.txt" \
    >/dev/null

echo "[INFO] Waiting for server readiness"
for ((i = 1; i <= SERVER_READY_TIMEOUT; i++)); do
    docker logs "${SERVER_CONTAINER}" > "${SERVER_LOG}" 2>&1 || true
    if grep -q "ORAM server ready" "${SERVER_LOG}"; then
        echo "[INFO] Server is ready"
        break
    fi
    if grep -qE "Exception|Error|OutOfMemoryError" "${SERVER_LOG}"; then
        echo "[ERROR] Server failed during startup"
        cat "${SERVER_LOG}"
        exit 1
    fi
    if [[ "${i}" -eq "${SERVER_READY_TIMEOUT}" ]]; then
        echo "[ERROR] Server readiness timeout"
        cat "${SERVER_LOG}"
        exit 1
    fi
    sleep 1
done

docker run -d \
    --name "${CLIENT_CONTAINER}" \
    --network "${NETWORK_NAME}" \
    --cap-add NET_ADMIN \
    -e JAVA_TOOL_OPTIONS="${JAVA_HEAP_OPTS}" \
    "${IMAGE_NAME}" \
    sleep infinity \
    >/dev/null

docker exec "${CLIENT_CONTAINER}" bash -lc \
    "tc qdisc add dev eth0 root tbf rate ${BANDWIDTH} burst ${BURST} latency ${TC_LATENCY}"

echo "[INFO] Verifying enforced bandwidth: ${BANDWIDTH}"
docker exec -d "${SERVER_CONTAINER}" iperf3 -s -p 5201 --one-off
sleep 1
docker exec "${CLIENT_CONTAINER}" iperf3 -c "${SERVER_CONTAINER}" -p 5201 -t 5 \
    > "${IPERF_LOG}" 2>&1 || true
cat "${IPERF_LOG}"

benchmark_class="oram.benchmark.ConcurrentSSEBenchmark"
if [[ "${BASELINE}" == "1" ]]; then
    if [[ "${N_CLIENTS}" != "1" ]]; then
        echo "[ERROR] BASELINE=1 requires N_CLIENTS=1."
        exit 1
    fi
    benchmark_class="oram.benchmark.BlockSSEBenchmark"
fi

echo "[INFO] Running ${benchmark_class}, queryMode=${QUERY_MODE}"
docker exec "${CLIENT_CONTAINER}" bash -lc \
    "${find_distribution}; \
     ${java_prefix} ${benchmark_class} \
     ${N_SEARCHES} ${N_CLIENTS} ${BID_EXPONENT} ${ROOT_BUCKET_SIZE} \
     ${COMPETITION_BUCKET_SIZE} ${BUCKET_SIZE} ${BLOCK_SIZE} \
     /app/DataSet/enron_20000.txt ${QUERY_MODE} ${SERVER_CONTAINER} ${SERVER_PORT}" \
    > "${CLIENT_LOG}" 2>&1

docker logs "${SERVER_CONTAINER}" > "${SERVER_LOG}" 2>&1 || true

echo "[INFO] Benchmark finished"
echo "[INFO] Client log: ${CLIENT_LOG}"
echo "[INFO] Server log: ${SERVER_LOG}"
grep -E "Completed searches|Useful chunk|Padding accesses|Wall-clock|throughput|latency|bandwidth" \
    "${CLIENT_LOG}" || true
