#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT_DIR/benchmark_logs/docker_bandwidth"
mkdir -p "$LOG_DIR"

IMAGE_NAME="${IMAGE_NAME:-opca-nettest}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
NETWORK_NAME="${NETWORK_NAME:-opca-net}"
STORAGE_CONTAINER="${STORAGE_CONTAINER:-opca-storage}"
PROXY_CONTAINER="${PROXY_CONTAINER:-opca-proxy}"
CLIENT_CONTAINER="${CLIENT_CONTAINER:-opca-client}"

BANDWIDTH="${BANDWIDTH:-1gbit}"
BURST="${BURST:-32mbit}"
LATENCY="${LATENCY:-400ms}"
STORAGE_PORT="${STORAGE_PORT:-9077}"
PROXY_PORT="${PROXY_PORT:-9078}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
READY_TIMEOUT="${READY_TIMEOUT:-2400}"

N_REQUESTS="${N_REQUESTS:-100}"
N_CLIENTS="${N_CLIENTS:-50}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BUCKET_SIZE="${BUCKET_SIZE:-4}"
BLOCK_SIZE="${BLOCK_SIZE:-1024}"
OPCA_K="${OPCA_K:-40}"
STORAGE_PROCESS_ID="${STORAGE_PROCESS_ID:-0}"
PROXY_PROCESS_ID="${PROXY_PROCESS_ID:-1}"

STORAGE_LOG="$LOG_DIR/storage.log"
PROXY_LOG="$LOG_DIR/proxy.log"
CLIENT_LOG="$LOG_DIR/client.log"
IPERF_CLIENT_PROXY_LOG="$LOG_DIR/iperf_client_proxy.log"
IPERF_PROXY_STORAGE_LOG="$LOG_DIR/iperf_proxy_storage.log"

cleanup() {
    docker rm -f "$CLIENT_CONTAINER" "$PROXY_CONTAINER" "$STORAGE_CONTAINER" >/dev/null 2>&1 || true
}

trap cleanup EXIT INT TERM

if [[ "$BUILD_IMAGE" == "1" ]] || ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    if [[ ! -f "$ROOT_DIR/build/install/Opca/smartrun.sh" ]]; then
        echo "[ERROR] Missing $ROOT_DIR/build/install/Opca/smartrun.sh"
        echo "[ERROR] Build the project first:"
        echo "  cd $ROOT_DIR"
        echo "  ./gradlew installDist"
        exit 1
    fi
    chmod +x "$ROOT_DIR/build/install/Opca/smartrun.sh"
    echo "[INFO] Building Docker image: $IMAGE_NAME"
    docker build -f "$ROOT_DIR/Dockerfile.nettest" -t "$IMAGE_NAME" "$ROOT_DIR"
else
    echo "[INFO] Reusing Docker image: $IMAGE_NAME"
fi

if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    echo "[INFO] Creating Docker network: $NETWORK_NAME"
    docker network create "$NETWORK_NAME" >/dev/null
fi

cleanup
apply_tc='tc qdisc add dev eth0 root tbf rate '"$BANDWIDTH"' burst '"$BURST"' latency '"$LATENCY"

docker run -d --name "$STORAGE_CONTAINER" --network "$NETWORK_NAME" --cap-add NET_ADMIN \
    -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" "$IMAGE_NAME" \
    bash -lc "$apply_tc && env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
              ./smartrun.sh opca.server.StorageServer \
              0.0.0.0 $STORAGE_PORT $BID_EXPONENT $BUCKET_SIZE $BLOCK_SIZE $STORAGE_PROCESS_ID" >/dev/null

for ((i = 1; i <= READY_TIMEOUT; i++)); do
    docker logs "$STORAGE_CONTAINER" > "$STORAGE_LOG" 2>&1 || true
    grep -q "Storage server listening" "$STORAGE_LOG" && break
    grep -qE "Exception|Error|BindException|OutOfMemoryError|Failed to" "$STORAGE_LOG" && { cat "$STORAGE_LOG"; exit 1; }
    [[ "$i" -eq "$READY_TIMEOUT" ]] && { cat "$STORAGE_LOG"; exit 1; }
    sleep 1
done

docker run -d --name "$PROXY_CONTAINER" --network "$NETWORK_NAME" --cap-add NET_ADMIN \
    -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" "$IMAGE_NAME" \
    bash -lc "$apply_tc && env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
              ./smartrun.sh opca.proxy.OpcaProxy \
              0.0.0.0 $PROXY_PORT $STORAGE_CONTAINER $STORAGE_PORT $BID_EXPONENT $BUCKET_SIZE $BLOCK_SIZE $OPCA_K $PROXY_PROCESS_ID" >/dev/null

for ((i = 1; i <= READY_TIMEOUT; i++)); do
    docker logs "$PROXY_CONTAINER" > "$PROXY_LOG" 2>&1 || true
    grep -q "Opca proxy ready" "$PROXY_LOG" && break
    grep -qE "Exception|Error|BindException|OutOfMemoryError|Failed to" "$PROXY_LOG" && { cat "$PROXY_LOG"; exit 1; }
    [[ "$i" -eq "$READY_TIMEOUT" ]] && { cat "$PROXY_LOG"; exit 1; }
    sleep 1
done

docker run -d --name "$CLIENT_CONTAINER" --network "$NETWORK_NAME" --cap-add NET_ADMIN \
    -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" "$IMAGE_NAME" sleep infinity >/dev/null
docker exec "$CLIENT_CONTAINER" bash -lc "$apply_tc"

docker exec -d "$PROXY_CONTAINER" bash -lc "iperf3 -s -p 5201 --one-off"
sleep 1
docker exec "$CLIENT_CONTAINER" bash -lc "iperf3 -c $PROXY_CONTAINER -p 5201 -t 5" > "$IPERF_CLIENT_PROXY_LOG" 2>&1 || true
cat "$IPERF_CLIENT_PROXY_LOG"

docker exec -d "$STORAGE_CONTAINER" bash -lc "iperf3 -s -p 5202 --one-off"
sleep 1
docker exec "$PROXY_CONTAINER" bash -lc "iperf3 -c $STORAGE_CONTAINER -p 5202 -t 5" > "$IPERF_PROXY_STORAGE_LOG" 2>&1 || true
cat "$IPERF_PROXY_STORAGE_LOG"

docker exec "$CLIENT_CONTAINER" bash -lc \
    "env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
     ./smartrun.sh opca.benchmark.OpcaBenchmarkClient \
     $N_CLIENTS $N_REQUESTS $BID_EXPONENT $BUCKET_SIZE $BLOCK_SIZE $PROXY_CONTAINER $PROXY_PORT" \
    > "$CLIENT_LOG" 2>&1

docker logs "$STORAGE_CONTAINER" > "$STORAGE_LOG" 2>&1 || true
docker logs "$PROXY_CONTAINER" > "$PROXY_LOG" 2>&1 || true

echo "[INFO] Benchmark finished"
echo "[INFO] Client log: $CLIENT_LOG"
echo "[INFO] Proxy log: $PROXY_LOG"
echo "[INFO] Storage log: $STORAGE_LOG"
echo
grep -E "Measured ops|Wall-clock time|Throughput" "$CLIENT_LOG" || true
