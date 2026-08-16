#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT_DIR/benchmark_logs/docker_bandwidth"
mkdir -p "$LOG_DIR"

IMAGE_NAME="${IMAGE_NAME:-mvporam-nettest}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
NETWORK_NAME="${NETWORK_NAME:-mvporam-net}"
SERVER_CONTAINER="${SERVER_CONTAINER:-mvporam-server}"
CLIENT_CONTAINER="${CLIENT_CONTAINER:-mvporam-client}"

BANDWIDTH="${BANDWIDTH:-1gbit}"
BURST="${BURST:-32mbit}"
LATENCY="${LATENCY:-400ms}"
SERVER_PORT="${SERVER_PORT:-9077}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-2400}"

N_REQUESTS="${N_REQUESTS:-1000}"
N_CLIENTS="${N_CLIENTS:-50}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
ZIPF_PARAMETER="${ZIPF_PARAMETER:-0.0}"

SERVER_LOG="$LOG_DIR/server.log"
CLIENT_LOG="$LOG_DIR/client.log"
IPERF_LOG="$LOG_DIR/iperf.log"

cleanup() {
    docker rm -f "$CLIENT_CONTAINER" "$SERVER_CONTAINER" >/dev/null 2>&1 || true
}

usage() {
    cat <<EOF
Usage:
  BANDWIDTH=1gbit N_CLIENTS=50 BLOCK_SIZE=1024 $0

Environment variables:
  BUILD_IMAGE       rebuild image when set to 1. Default: $BUILD_IMAGE
  BANDWIDTH         tc rate, e.g. 100mbit, 1gbit, 10gbit. Default: $BANDWIDTH
  N_REQUESTS        requests per client. Default: $N_REQUESTS
  N_CLIENTS         number of clients and server maxClients. Default: $N_CLIENTS
  BID_EXPONENT      log2(N). Default: $BID_EXPONENT
  BUCKET_SIZE       Path ORAM bucket size Z. Default: $BUCKET_SIZE
  BLOCK_SIZE        block size in bytes. Default: $BLOCK_SIZE
  ZIPF_PARAMETER    0 for uniform; >0 for Zipf exponent. Default: $ZIPF_PARAMETER
  SERVER_PORT       server port. Default: $SERVER_PORT
  JAVA_HEAP_OPTS    JVM heap opts. Default: $JAVA_HEAP_OPTS

Logs:
  $LOG_DIR
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

trap cleanup EXIT INT TERM

if [[ "$BUILD_IMAGE" == "1" ]] || ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    if [[ ! -f "$ROOT_DIR/build/install/SingleServerORAM/smartrun.sh" ]]; then
        echo "[ERROR] Missing $ROOT_DIR/build/install/SingleServerORAM/smartrun.sh"
        echo "[ERROR] Build the project first:"
        echo "  cd $ROOT_DIR"
        echo "  ./gradlew installDist"
        exit 1
    fi
    sed -i 's/\r$//' "$ROOT_DIR/build/install/SingleServerORAM/smartrun.sh"
    chmod +x "$ROOT_DIR/build/install/SingleServerORAM/smartrun.sh"
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

# MVP uses an edge-based tree height: height h has 2^h leaves and h+1 levels.
TREE_HEIGHT="$BID_EXPONENT"
INITIAL_CLIENT_ID=1
SERVER_ID=0
MEASUREMENT_LEADER=true

echo "[INFO] Starting server container"
docker run -d \
    --name "$SERVER_CONTAINER" \
    --network "$NETWORK_NAME" \
    --cap-add NET_ADMIN \
    -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
    "$IMAGE_NAME" \
    bash -lc "tc qdisc add dev eth0 root tbf rate $BANDWIDTH burst $BURST latency $LATENCY && \
              env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
              ./smartrun.sh oram.server.ORAMServer \
              $N_CLIENTS 0.0.0.0 $SERVER_PORT $SERVER_ID" \
    >/dev/null

echo "[INFO] Waiting for server readiness"
for ((i = 1; i <= SERVER_READY_TIMEOUT; i++)); do
    docker logs "$SERVER_CONTAINER" > "$SERVER_LOG" 2>&1 || true
    if grep -q "Ready to process operations" "$SERVER_LOG"; then
        echo "[INFO] Server is ready"
        break
    fi
    if grep -qE "Exception|Error|BindException|OutOfMemoryError" "$SERVER_LOG"; then
        echo "[ERROR] Server failed during startup"
        cat "$SERVER_LOG"
        exit 1
    fi
    if [[ "$i" -eq "$SERVER_READY_TIMEOUT" ]]; then
        echo "[ERROR] Server did not become ready within ${SERVER_READY_TIMEOUT}s"
        cat "$SERVER_LOG"
        exit 1
    fi
    sleep 1
done

echo "[INFO] Starting client container"
docker run -d \
    --name "$CLIENT_CONTAINER" \
    --network "$NETWORK_NAME" \
    --cap-add NET_ADMIN \
    -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
    "$IMAGE_NAME" \
    sleep infinity \
    >/dev/null

echo "[INFO] Applying client bandwidth limit: $BANDWIDTH"
docker exec "$CLIENT_CONTAINER" bash -lc "tc qdisc add dev eth0 root tbf rate $BANDWIDTH burst $BURST latency $LATENCY"

echo "[INFO] Verifying bandwidth with iperf3"
docker exec -d "$SERVER_CONTAINER" bash -lc "iperf3 -s -p 5201 --one-off"
sleep 1
docker exec "$CLIENT_CONTAINER" bash -lc "iperf3 -c $SERVER_CONTAINER -p 5201 -t 5" > "$IPERF_LOG" 2>&1 || true
cat "$IPERF_LOG"

if [[ "$ZIPF_PARAMETER" == "0" || "$ZIPF_PARAMETER" == "0.0" ]]; then
    echo "[INFO] Running MVP-ORAM benchmark with uniform BID sampling"
else
    echo "[INFO] Running MVP-ORAM benchmark with Zipf alpha=$ZIPF_PARAMETER"
fi
docker exec "$CLIENT_CONTAINER" bash -lc \
    "env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
     ./smartrun.sh oram.benchmark.ORAMBenchmarkClient \
     $INITIAL_CLIENT_ID $N_CLIENTS $N_REQUESTS $TREE_HEIGHT $BUCKET_SIZE $BLOCK_SIZE $ZIPF_PARAMETER $SERVER_CONTAINER $SERVER_PORT $MEASUREMENT_LEADER" \
    > "$CLIENT_LOG" 2>&1

docker logs "$SERVER_CONTAINER" > "$SERVER_LOG" 2>&1 || true

echo "[INFO] Benchmark finished"
echo "[INFO] Client log: $CLIENT_LOG"
echo "[INFO] Server log: $SERVER_LOG"
echo "[INFO] iperf3 log: $IPERF_LOG"
echo
grep -E "Measured ops|Wall-clock time|Throughput" "$CLIENT_LOG" || true
