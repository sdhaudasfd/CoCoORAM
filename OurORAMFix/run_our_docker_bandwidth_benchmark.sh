#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT_DIR/benchmark_logs/docker_bandwidth"
mkdir -p "$LOG_DIR"

IMAGE_NAME="${IMAGE_NAME:-ouroram-nettest}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
NETWORK_NAME="${NETWORK_NAME:-ouroram-net}"
SERVER_CONTAINER="${SERVER_CONTAINER:-ouroram-server}"
CLIENT_CONTAINER="${CLIENT_CONTAINER:-ouroram-client}"

BANDWIDTH="${BANDWIDTH:-1gbit}"
BURST="${BURST:-32mbit}"
LATENCY="${LATENCY:-400ms}"
SERVER_PORT="${SERVER_PORT:-9077}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-2400}"
CLIENT_IO_THREADS="${CLIENT_IO_THREADS:-16}"
SHARED_CLIENT_IO="${SHARED_CLIENT_IO:-0}"
DIRECT_CLIENT_RESPONSES="${DIRECT_CLIENT_RESPONSES:-0}"
COARSE_ADMISSION_GROUPS="${COARSE_ADMISSION_GROUPS:-0}"
ROUND_ROBIN_ADMISSION_GROUPS="${ROUND_ROBIN_ADMISSION_GROUPS:-0}"
RETAIN_ALL_HISTORY="${RETAIN_ALL_HISTORY:-1}"

N_REQUESTS="${N_REQUESTS:-1000}"
N_CLIENTS="${N_CLIENTS:-50}"
MAX_CONCURRENT_CLIENTS="${MAX_CONCURRENT_CLIENTS:-$N_CLIENTS}"
BID_EXPONENT="${BID_EXPONENT:-18}"
ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-1}"
COMPETITION_BUCKET_SIZE="${COMPETITION_BUCKET_SIZE:-1}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
BLOCK_SIZE="${BLOCK_SIZE:-1024}"

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
  BUILD_IMAGE                rebuild image when set to 1. Default: $BUILD_IMAGE
  BANDWIDTH                  tc rate, e.g. 100mbit, 1gbit, 10gbit. Default: $BANDWIDTH
  N_REQUESTS                 requests per client. Default: $N_REQUESTS
  N_CLIENTS                  total number of benchmark clients. Default: $N_CLIENTS
  MAX_CONCURRENT_CLIENTS     protocol window c. Default: $MAX_CONCURRENT_CLIENTS
  BID_EXPONENT               log2(N). Default: $BID_EXPONENT
  ROOT_BUCKET_SIZE           root bucket base size. Default: $ROOT_BUCKET_SIZE
  COMPETITION_BUCKET_SIZE    competition bucket base size. Default: $COMPETITION_BUCKET_SIZE
  BUCKET_SIZE                non-competition bucket size. Default: $BUCKET_SIZE
  BLOCK_SIZE                 block size in bytes. Default: $BLOCK_SIZE
  SERVER_PORT                server port. Default: $SERVER_PORT
  JAVA_HEAP_OPTS             JVM heap opts. Default: $JAVA_HEAP_OPTS
  CLIENT_IO_THREADS          shared client Netty I/O threads. Default: $CLIENT_IO_THREADS
  SHARED_CLIENT_IO           share Netty I/O threads across clients (0/1). Default: $SHARED_CLIENT_IO
  DIRECT_CLIENT_RESPONSES    bypass per-client response thread (0/1). Default: $DIRECT_CLIENT_RESPONSES
  COARSE_ADMISSION_GROUPS    finish one group before admitting the next (0/1). Default: $COARSE_ADMISSION_GROUPS
  ROUND_ROBIN_ADMISSION_GROUPS rotate request batches after every access (0/1). Default: $ROUND_ROBIN_ADMISSION_GROUPS
  RETAIN_ALL_HISTORY         retain UM/RL versions for delayed clients (0/1). Default: $RETAIN_ALL_HISTORY

Logs:
  $LOG_DIR
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

trap cleanup EXIT INT TERM

if (( MAX_CONCURRENT_CLIENTS < 1 || MAX_CONCURRENT_CLIENTS > N_CLIENTS )); then
    echo "[ERROR] MAX_CONCURRENT_CLIENTS must be in [1, N_CLIENTS]"
    exit 1
fi
if [[ "$COARSE_ADMISSION_GROUPS" == "1" && "$RETAIN_ALL_HISTORY" != "1" ]]; then
    echo "[ERROR] COARSE_ADMISSION_GROUPS=1 requires RETAIN_ALL_HISTORY=1"
    echo "[ERROR] Sleeping client groups otherwise miss evicted UM/RL versions."
    exit 1
fi
if [[ "$ROUND_ROBIN_ADMISSION_GROUPS" == "1" && "$RETAIN_ALL_HISTORY" != "1" ]]; then
    echo "[ERROR] ROUND_ROBIN_ADMISSION_GROUPS=1 requires RETAIN_ALL_HISTORY=1"
    exit 1
fi
if [[ "$COARSE_ADMISSION_GROUPS" == "1" && "$ROUND_ROBIN_ADMISSION_GROUPS" == "1" ]]; then
    echo "[ERROR] COARSE_ADMISSION_GROUPS and ROUND_ROBIN_ADMISSION_GROUPS are mutually exclusive"
    exit 1
fi
if [[ "$COARSE_ADMISSION_GROUPS" == "1" ]] && (( N_CLIENTS % MAX_CONCURRENT_CLIENTS != 0 )); then
    echo "[ERROR] Coarse admission groups require N_CLIENTS to be divisible by MAX_CONCURRENT_CLIENTS."
    echo "[ERROR] Use COARSE_ADMISSION_GROUPS=0 for sliding FIFO admission with mixed timesteps."
    exit 1
fi
if [[ "$ROUND_ROBIN_ADMISSION_GROUPS" == "1" ]] && (( N_CLIENTS % MAX_CONCURRENT_CLIENTS != 0 )); then
    echo "[ERROR] Round-robin admission requires N_CLIENTS to be divisible by MAX_CONCURRENT_CLIENTS."
    exit 1
fi

if [[ "$BUILD_IMAGE" == "1" ]] || ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    if [[ ! -f "$ROOT_DIR/build/install/OurORAMFix/smartrun.sh" ]]; then
        echo "[ERROR] Missing $ROOT_DIR/build/install/OurORAMFix/smartrun.sh"
        echo "[ERROR] Build the project first:"
        echo "  cd $ROOT_DIR"
        echo "  ./gradlew installDist"
        exit 1
    fi
    chmod +x "$ROOT_DIR/build/install/OurORAMFix/smartrun.sh"

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

echo "[INFO] Starting server container"
docker run -d \
    --name "$SERVER_CONTAINER" \
    --network "$NETWORK_NAME" \
    --cap-add NET_ADMIN \
    -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
    "$IMAGE_NAME" \
    bash -lc "tc qdisc add dev eth0 root tbf rate $BANDWIDTH burst $BURST latency $LATENCY && \
              env JAVA_OPTS=\"\$JAVA_HEAP_OPTS -Doram.retainAllHistory=$RETAIN_ALL_HISTORY\" \
              JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS -Doram.retainAllHistory=$RETAIN_ALL_HISTORY\" \
              ./smartrun.sh oram.server.ORAMServer \
              $N_CLIENTS $MAX_CONCURRENT_CLIENTS $BID_EXPONENT $ROOT_BUCKET_SIZE $COMPETITION_BUCKET_SIZE $BUCKET_SIZE $BLOCK_SIZE 0.0.0.0 $SERVER_PORT" \
    >/dev/null

echo "[INFO] Waiting for server readiness"
for ((i = 1; i <= SERVER_READY_TIMEOUT; i++)); do
    docker logs "$SERVER_CONTAINER" > "$SERVER_LOG" 2>&1 || true
    if grep -q "ORAM server ready" "$SERVER_LOG"; then
        echo "[INFO] Server is ready"
        break
    fi
    if grep -qE "Exception|Error|BindException|OutOfMemoryError|Usage: oram.server.ORAMServer" "$SERVER_LOG"; then
        echo "[ERROR] Server failed during startup"
        cat "$SERVER_LOG"
        exit 1
    fi
    if [[ "$(docker inspect -f '{{.State.Running}}' "$SERVER_CONTAINER" 2>/dev/null || echo false)" != "true" ]]; then
        echo "[ERROR] Server container exited before becoming ready"
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

echo "[INFO] Running OurORAM benchmark"
echo "[INFO] total clients=$N_CLIENTS, protocol window=$MAX_CONCURRENT_CLIENTS"
echo "[INFO] client transport: sharedIo=$SHARED_CLIENT_IO ioThreads=$CLIENT_IO_THREADS directResponses=$DIRECT_CLIENT_RESPONSES"
echo "[INFO] admission: coarseGroups=$COARSE_ADMISSION_GROUPS roundRobinGroups=$ROUND_ROBIN_ADMISSION_GROUPS retainAllHistory=$RETAIN_ALL_HISTORY"
docker exec "$CLIENT_CONTAINER" bash -lc \
    "env JAVA_OPTS=\"\$JAVA_HEAP_OPTS -Doram.clientIoThreads=$CLIENT_IO_THREADS -Doram.sharedClientIo=$SHARED_CLIENT_IO -Doram.directClientResponses=$DIRECT_CLIENT_RESPONSES -Doram.coarseAdmissionGroups=$COARSE_ADMISSION_GROUPS -Doram.roundRobinAdmissionGroups=$ROUND_ROBIN_ADMISSION_GROUPS\" \
     JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS -Doram.clientIoThreads=$CLIENT_IO_THREADS -Doram.sharedClientIo=$SHARED_CLIENT_IO -Doram.directClientResponses=$DIRECT_CLIENT_RESPONSES -Doram.coarseAdmissionGroups=$COARSE_ADMISSION_GROUPS -Doram.roundRobinAdmissionGroups=$ROUND_ROBIN_ADMISSION_GROUPS\" \
     ./smartrun.sh oram.benchmark.OursBenchmark \
     $N_REQUESTS $N_CLIENTS $MAX_CONCURRENT_CLIENTS $BID_EXPONENT $ROOT_BUCKET_SIZE $COMPETITION_BUCKET_SIZE $BUCKET_SIZE $BLOCK_SIZE $SERVER_CONTAINER $SERVER_PORT" \
    > "$CLIENT_LOG" 2>&1

docker logs "$SERVER_CONTAINER" > "$SERVER_LOG" 2>&1 || true

echo "[INFO] Benchmark finished"
echo "[INFO] Client log: $CLIENT_LOG"
echo "[INFO] Server log: $SERVER_LOG"
echo "[INFO] iperf3 log: $IPERF_LOG"

echo
grep -E "Measured ops|Wall-clock time|Throughput|Avg .*bandwidth|Client resident storage|access diagnostic summary|access timing summary" "$CLIENT_LOG" || true
grep -E "Server total storage|server diagnostic summary" "$SERVER_LOG" | tail -2 || true
