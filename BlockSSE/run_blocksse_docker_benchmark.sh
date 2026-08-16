#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE_NAME="${IMAGE_NAME:-blocksse-nettest}"
NETWORK_NAME="${NETWORK_NAME:-blocksse-net}"
SERVER_NAME="${SERVER_NAME:-blocksse-server}"
CLIENT_NAME="${CLIENT_NAME:-blocksse-client}"
SERVER_PORT="${SERVER_PORT:-9077}"
SERVER_IP="${SERVER_IP:-172.31.0.2}"
CLIENT_IP="${CLIENT_IP:-172.31.0.3}"
N_CLIENTS="${N_CLIENTS:-5}"
N_SEARCHES="${N_SEARCHES:-1000}"
BID_EXPONENT="${BID_EXPONENT:-17}"
ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-3}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
BLOCK_SIZE="${BLOCK_SIZE:-256}"
QUERY_MODE="${QUERY_MODE:-uniform}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms16g -Xmx256g}"
BUILD_PROJECT="${BUILD_PROJECT:-1}"
BUILD_IMAGE="${BUILD_IMAGE:-1}"
VERIFY_BANDWIDTH="${VERIFY_BANDWIDTH:-1}"
DATASET_HOST="${DATASET_HOST:-${ROOT_DIR}/../OurSSE/DataSet/enron_20000.txt}"
LOG_DIR="${LOG_DIR:-${ROOT_DIR}/benchmark_logs/blocksse_docker}"

SERVER_LOG="${LOG_DIR}/server.log"
CLIENT_LOG="${LOG_DIR}/client.log"
CONTAINER_DATASET="/app/DataSet/enron.txt"

cleanup() {
    docker rm -f "${CLIENT_NAME}" "${SERVER_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

if [[ ! -f "${DATASET_HOST}" ]]; then
    echo "[ERROR] Dataset not found: ${DATASET_HOST}" >&2
    exit 1
fi

mkdir -p "${LOG_DIR}"
: >"${SERVER_LOG}"
: >"${CLIENT_LOG}"
cleanup

if [[ "${BUILD_PROJECT}" == "1" ]]; then
    (cd "${ROOT_DIR}" && ./gradlew installDist)
fi
if [[ "${BUILD_IMAGE}" == "1" ]]; then
    echo "[INFO] Building Docker image: ${IMAGE_NAME}"
    docker build -f "${ROOT_DIR}/Dockerfile.blocksse" -t "${IMAGE_NAME}" "${ROOT_DIR}"
else
    echo "[INFO] Reusing Docker image: ${IMAGE_NAME}"
fi

docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1 ||
    docker network create --subnet 172.31.0.0/16 "${NETWORK_NAME}" >/dev/null

echo "[INFO] Starting BlockSSE server"
docker run -d --name "${SERVER_NAME}" \
    --network "${NETWORK_NAME}" --ip "${SERVER_IP}" \
    --cap-add NET_ADMIN \
    -e JAVA_TOOL_OPTIONS="${JAVA_HEAP_OPTS}" \
    -v "${DATASET_HOST}:${CONTAINER_DATASET}:ro" \
    "${IMAGE_NAME}" \
    java \
    -Djava.security.properties=./config/java.security \
    -Dlogback.configurationFile=./config/logback.xml \
    -cp "lib/*" \
    oram.server.BlockSSEServer \
    "${BID_EXPONENT}" "${ROOT_BUCKET_SIZE}" "${BUCKET_SIZE}" "${BLOCK_SIZE}" \
    0.0.0.0 "${SERVER_PORT}" "${CONTAINER_DATASET}" >/dev/null

echo "[INFO] Waiting for server readiness"
for _ in $(seq 1 600); do
    docker logs "${SERVER_NAME}" >"${SERVER_LOG}" 2>&1 || true
    if grep -q "BlockSSE server ready" "${SERVER_LOG}"; then
        break
    fi
    if grep -Eq "Exception|OutOfMemoryError|ERROR" "${SERVER_LOG}"; then
        cat "${SERVER_LOG}" >&2
        exit 1
    fi
    sleep 1
done
if ! grep -q "BlockSSE server ready" "${SERVER_LOG}"; then
    echo "[ERROR] Server readiness timeout" >&2
    cat "${SERVER_LOG}" >&2
    exit 1
fi

docker exec "${SERVER_NAME}" tc qdisc replace dev eth0 root tbf \
    rate "${BANDWIDTH}" burst 16mb latency 100ms
docker exec -d "${SERVER_NAME}" iperf3 -s

docker run -d --name "${CLIENT_NAME}" \
    --network "${NETWORK_NAME}" --ip "${CLIENT_IP}" \
    --cap-add NET_ADMIN \
    -e JAVA_TOOL_OPTIONS="${JAVA_HEAP_OPTS}" \
    -v "${DATASET_HOST}:${CONTAINER_DATASET}:ro" \
    "${IMAGE_NAME}" sleep infinity >/dev/null
docker exec "${CLIENT_NAME}" tc qdisc replace dev eth0 root tbf \
    rate "${BANDWIDTH}" burst 16mb latency 100ms

if [[ "${VERIFY_BANDWIDTH}" == "1" ]]; then
    echo "[INFO] Verifying enforced bandwidth: ${BANDWIDTH}"
    docker exec "${CLIENT_NAME}" iperf3 -c "${SERVER_IP}" -t 5
fi

echo "[INFO] Running serialized BlockSSE benchmark"
set +e
docker exec "${CLIENT_NAME}" \
    java \
    -Djava.security.properties=./config/java.security \
    -Dlogback.configurationFile=./config/logback.xml \
    -cp "lib/*" \
    oram.benchmark.BlockSSEBenchmark \
    "${N_SEARCHES}" "${N_CLIENTS}" "${BID_EXPONENT}" \
    "${ROOT_BUCKET_SIZE}" "${BUCKET_SIZE}" "${BLOCK_SIZE}" \
    "${CONTAINER_DATASET}" "${QUERY_MODE}" "${SERVER_IP}" "${SERVER_PORT}" \
    >"${CLIENT_LOG}" 2>&1
RC=$?
set -e
docker logs "${SERVER_NAME}" >"${SERVER_LOG}" 2>&1 || true
cat "${CLIENT_LOG}"
if [[ "${RC}" -ne 0 ]]; then
    echo "[ERROR] Client failed with rc=${RC}" >&2
    exit "${RC}"
fi

echo "[INFO] Client log: ${CLIENT_LOG}"
echo "[INFO] Server log: ${SERVER_LOG}"
