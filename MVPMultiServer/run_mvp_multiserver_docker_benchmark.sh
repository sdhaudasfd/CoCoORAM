#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT_DIR/benchmark_logs/docker_multiserver"
RESULT_DIR="$ROOT_DIR/benchmark_results"
mkdir -p "$LOG_DIR" "$RESULT_DIR"

IMAGE_NAME="${IMAGE_NAME:-mvporam-multiserver-nettest}"
BUILD_PROJECT="${BUILD_PROJECT:-0}"
BUILD_IMAGE="${BUILD_IMAGE:-0}"
NETWORK_NAME="${NETWORK_NAME:-mvp-ms-net}"

BANDWIDTH="${BANDWIDTH:-10gbit}"
BURST="${BURST:-128mbit}"
LATENCY="${LATENCY:-400ms}"
SHAPE_CLIENT="${SHAPE_CLIENT:-0}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms8g -Xmx128g"}"
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-600}"
BENCHMARK_TIMEOUT_SECONDS="${BENCHMARK_TIMEOUT_SECONDS:-7200}"
SETTING_TIMEOUT_SECONDS="${SETTING_TIMEOUT_SECONDS:-$((SERVER_READY_TIMEOUT + BENCHMARK_TIMEOUT_SECONDS + 30000))}"
SERVER_START_DELAY_SECONDS="${SERVER_START_DELAY_SECONDS:-5}"
BFT_STABILIZE_SECONDS="${BFT_STABILIZE_SECONDS:-5}"

REPLICA_VALUES="${REPLICA_VALUES:-10}"
CLIENT_VALUES="${CLIENT_VALUES:-50}"
N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BUCKET_SIZE="${BUCKET_SIZE:-4}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
ZIPF_PARAMETER="${ZIPF_PARAMETER:-1.0}"
MAX_CONCURRENT_CLIENTS="${MAX_CONCURRENT_CLIENTS:-50}"
SERVER_PORT="${SERVER_PORT:-11000}"

BANDWIDTH_LABEL="$(echo "$BANDWIDTH" | tr -c '[:alnum:]_-' '_')"
RESULT_CSV="${RESULT_CSV:-$RESULT_DIR/MVPMultiServer_${BANDWIDTH_LABEL}_results.csv}"
SERVER_DETAIL_CSV="${SERVER_DETAIL_CSV:-$RESULT_DIR/MVPMultiServer_${BANDWIDTH_LABEL}_server_network.csv}"
APPEND_RESULT="${APPEND_RESULT:-0}"
CSV_HEADER="scheme,replicas,f,clients,logN,blockSize,requestsPerClient,bandwidth,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,serverRxBytesTotal,serverTxBytesTotal,serverNetworkBytesTotal,serverTxBytesAvg,serverTxBytesMax,serverWallClockSecAvg,serverWallClockSecMax,status,tag"
SERVER_DETAIL_HEADER="tag,replica,rxBytes,txBytes,totalBytes,wallClockSec"

CONTAINERS=()

usage() {
    cat <<EOF
Usage:
  BUILD_PROJECT=1 BUILD_IMAGE=1 BANDWIDTH=10gbit $0

Environment variables:
  BUILD_PROJECT             run Gradle installDist first. Default: $BUILD_PROJECT
  BUILD_IMAGE               rebuild Docker image. Default: $BUILD_IMAGE
  BANDWIDTH                 tc rate per shaped container. Default: $BANDWIDTH
  SHAPE_CLIENT              also shape the client container when 1. Default: $SHAPE_CLIENT
  REPLICA_VALUES            replica counts. Default: "$REPLICA_VALUES"
  CLIENT_VALUES             client counts. Default: "$CLIENT_VALUES"
  N_REQUESTS                requests per client. Default: $N_REQUESTS
  BID_EXPONENT              log2(N). Default: $BID_EXPONENT
  BUCKET_SIZE               bucket size. Default: $BUCKET_SIZE
  BLOCK_SIZE                block size. Default: $BLOCK_SIZE
  ZIPF_PARAMETER            Zipf parameter. Default: $ZIPF_PARAMETER
  MAX_CONCURRENT_CLIENTS    server concurrency cap. Default: $MAX_CONCURRENT_CLIENTS
  SETTING_TIMEOUT_SECONDS   hard timeout for one complete setting. Default: $SETTING_TIMEOUT_SECONDS
  RESULT_CSV                output CSV. Default: $RESULT_CSV

Notes:
  Replica count 1 uses the single-server implementation.
  Replica counts 4/7/10 map to BFT f=1/2/3.
EOF
}

cleanup() {
    if [[ "${#CONTAINERS[@]}" -gt 0 ]]; then
        docker rm -f "${CONTAINERS[@]}" >/dev/null 2>&1 || true
    fi
}

cleanup_fixed_containers() {
    local names=(mvpms-client)
    for ((i = 0; i < 10; i++)); do
        names+=("mvpms-server${i}")
    done
    docker rm -f "${names[@]}" >/dev/null 2>&1 || true
}

trap cleanup EXIT INT TERM

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

run_gradle() {
    if command -v gradle >/dev/null 2>&1; then
        gradle "$@"
    else
        ./gradlew "$@"
    fi
}

if [[ "$BUILD_PROJECT" == "1" ]]; then
    echo "[INFO] Building MVPORAMMultiServer"
    (cd "$ROOT_DIR" && run_gradle installDist)
fi

if [[ ! -f "$ROOT_DIR/build/install/MVPORAM/smartrun.sh" ]]; then
    echo "[ERROR] Missing $ROOT_DIR/build/install/MVPORAM/smartrun.sh"
    echo "Build first:"
    echo "  cd $ROOT_DIR"
    echo "  ./gradlew installDist"
    exit 1
fi
chmod +x "$ROOT_DIR/build/install/MVPORAM/smartrun.sh"

if [[ "$BUILD_IMAGE" == "1" ]] || ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    echo "[INFO] Building Docker image: $IMAGE_NAME"
    docker build -f "$ROOT_DIR/Dockerfile.nettest" -t "$IMAGE_NAME" "$ROOT_DIR"
else
    echo "[INFO] Reusing Docker image: $IMAGE_NAME"
fi

if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    echo "[INFO] Creating Docker network: $NETWORK_NAME"
    docker network create "$NETWORK_NAME" >/dev/null
fi

if [[ "$APPEND_RESULT" != "1" || ! -f "$RESULT_CSV" ]]; then
    echo "$CSV_HEADER" > "$RESULT_CSV"
fi
if [[ "$APPEND_RESULT" != "1" || ! -f "$SERVER_DETAIL_CSV" ]]; then
    echo "$SERVER_DETAIL_HEADER" > "$SERVER_DETAIL_CSV"
fi

generate_config() {
    local replica_count="$1"
    local f="$2"
    local config_dir="$3"
    local initial_view

    initial_view="$(seq -s, 0 $((replica_count - 1)))"

    rm -rf "$config_dir"
    mkdir -p "$config_dir"
    cp -R "$ROOT_DIR/build/install/MVPORAM/config/." "$config_dir/"

    {
        echo "# Generated by run_mvp_multiserver_docker_benchmark.sh"
        echo "# server id, address and port"
        for ((i = 0; i < replica_count; i++)); do
            echo "$i mvpms-server${i} $SERVER_PORT $((SERVER_PORT + 1))"
        done
        echo "7001 127.0.0.1 11100"
    } > "$config_dir/hosts.config"

    sed -i \
        -e "s/^system.servers.num[[:space:]]*=.*/system.servers.num = $replica_count/" \
        -e "s/^system.servers.f[[:space:]]*=.*/system.servers.f = $f/" \
        -e "s/^system.totalordermulticast.timeout[[:space:]]*=.*/system.totalordermulticast.timeout = 30000/" \
        -e "s/^system.initial.view[[:space:]]*=.*/system.initial.view = $initial_view/" \
        "$config_dir/system.config"

    sed -i \
        -e 's/<logger name="measurement" level="INFO"\/>/<logger name="measurement" level="OFF"\/>/' \
        "$config_dir/logback.xml"
}

wait_for_log() {
    local container="$1"
    local log_file="$2"
    local pattern="$3"
    local label="$4"

    for ((i = 1; i <= SERVER_READY_TIMEOUT; i++)); do
        docker logs "$container" > "$log_file" 2>&1 || true
        docker exec "$container" bash -lc 'if [[ -f /tmp/server.log ]]; then cat /tmp/server.log; fi' >> "$log_file" 2>/dev/null || true
        if grep -q "$pattern" "$log_file"; then
            return 0
        fi

        local running
        running="$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo false)"
        if [[ "$running" != "true" ]]; then
            echo "[ERROR] $label failed during startup"
            cat "$log_file"
            return 1
        fi

        if grep -qE "BindException|OutOfMemoryError|Cannot|FATAL" "$log_file"; then
            echo "[ERROR] $label failed during startup"
            cat "$log_file"
            return 1
        fi
        sleep 1
    done

    echo "[ERROR] $label did not become ready within ${SERVER_READY_TIMEOUT}s"
    cat "$log_file"
    return 1
}

wait_for_all_servers_ready() {
    local replicas="$1"
    local run_dir="$2"
    local pattern="$3"
    local all_ready

    for ((attempt = 1; attempt <= SERVER_READY_TIMEOUT; attempt++)); do
        dump_server_logs "$replicas" "$run_dir"
        all_ready=1

        for ((i = 0; i < replicas; i++)); do
            local container="mvpms-server${i}"
            local log_file="$run_dir/server${i}.log"
            local running

            running="$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo false)"
            if [[ "$running" != "true" ]]; then
                echo "[ERROR] server $i is not running during startup"
                cat "$log_file" || true
                return 1
            fi

            if grep -qE "BindException|OutOfMemoryError|Cannot|FATAL" "$log_file"; then
                echo "[ERROR] server $i failed during startup"
                cat "$log_file" || true
                return 1
            fi

            if ! grep -q "$pattern" "$log_file"; then
                all_ready=0
            fi
        done

        if [[ "$all_ready" == "1" ]]; then
            return 0
        fi

        sleep 1
    done

    echo "[ERROR] not all servers became ready within ${SERVER_READY_TIMEOUT}s"
    for ((i = 0; i < replicas; i++)); do
        echo "--- server $i log tail ---"
        tail -n 80 "$run_dir/server${i}.log" || true
    done
    return 1
}

dump_server_logs() {
    local replicas="$1"
    local run_dir="$2"

    for ((i = 0; i < replicas; i++)); do
        docker logs "mvpms-server${i}" > "$run_dir/server${i}.log" 2>&1 || true
        docker exec "mvpms-server${i}" bash -lc 'if [[ -f /tmp/server.log ]]; then cat /tmp/server.log; fi' >> "$run_dir/server${i}.log" 2>/dev/null || true
    done
}

verify_servers_running() {
    local replicas="$1"
    local run_dir="$2"

    dump_server_logs "$replicas" "$run_dir"
    for ((i = 0; i < replicas; i++)); do
        local running
        running="$(docker inspect -f '{{.State.Running}}' "mvpms-server${i}" 2>/dev/null || echo false)"
        if [[ "$running" != "true" ]]; then
            echo "[ERROR] server $i is not running after launch"
            cat "$run_dir/server${i}.log" || true
            return 1
        fi
    done
    return 0
}

container_stat_bytes() {
    local container="$1"
    local direction="$2"
    docker exec "$container" bash -lc "cat /sys/class/net/eth0/statistics/${direction}_bytes" 2>/dev/null || echo 0
}

append_failed_result() {
    local replicas="$1"
    local f="$2"
    local clients="$3"
    local tag="$4"
    echo "MVPORAM-Multi,$replicas,$f,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,NA,NA,NA,NA,NA,NA,NA,NA,NA,NA,NA,FAILED,$tag" >> "$RESULT_CSV"
}

run_one() {
    local replicas="$1"
    local clients="$2"
    local f
    local tag="MVPMS_n${replicas}_c${clients}_N${BID_EXPONENT}_B${BLOCK_SIZE}_${BANDWIDTH_LABEL}"
    local run_dir="$LOG_DIR/$tag"
    local config_dir="$run_dir/config"
    local client_container="mvpms-client"
    local client_log="$run_dir/client.log"
    local watchdog_pid=""

    mkdir -p "$run_dir"
    CONTAINERS=()

    if [[ "$replicas" == "1" ]]; then
        f=0
    else
        f=$(( (replicas - 1) / 3 ))
    fi

    echo
    echo "============================================================"
    echo "[INFO] $tag"
    echo "[INFO] replicas=$replicas, f=$f, clients=$clients, bandwidth=$BANDWIDTH"
    echo "============================================================"

    cleanup_fixed_containers
    (
        sleep "$SETTING_TIMEOUT_SECONDS"
        echo "[WATCHDOG] $tag exceeded ${SETTING_TIMEOUT_SECONDS}s; removing containers to unblock the suite." | tee "$run_dir/watchdog.log"
        docker logs "$client_container" > "$run_dir/client.watchdog.log" 2>&1 || true
        for ((j = 0; j < replicas; j++)); do
            docker logs "mvpms-server${j}" > "$run_dir/server${j}.watchdog.log" 2>&1 || true
        done
        cleanup_fixed_containers
    ) &
    watchdog_pid=$!

    stop_watchdog() {
        if [[ -n "$watchdog_pid" ]] && kill -0 "$watchdog_pid" >/dev/null 2>&1; then
            kill "$watchdog_pid" >/dev/null 2>&1 || true
            wait "$watchdog_pid" >/dev/null 2>&1 || true
        fi
    }

    generate_config "$replicas" "$f" "$config_dir"
    CONTAINERS=()

    for ((i = 0; i < replicas; i++)); do
        local server_container="mvpms-server${i}"
        local server_log="$run_dir/server${i}.log"
        CONTAINERS+=("$server_container")

        if [[ "$replicas" == "1" ]]; then
            docker run -d \
                --name "$server_container" \
                --hostname "$server_container" \
                --network "$NETWORK_NAME" \
                --network-alias "$server_container" \
                --cap-add NET_ADMIN \
                -v "$config_dir:/app/MVPORAM/build/install/MVPORAM/config:ro" \
                -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
                "$IMAGE_NAME" \
                bash -lc "tc qdisc add dev eth0 root tbf rate $BANDWIDTH burst $BURST latency $LATENCY && \
                          env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
                          ./smartrun.sh oram.single.server.ORAMSingleServer \
                          $MAX_CONCURRENT_CLIENTS 0.0.0.0 $SERVER_PORT 0" \
                >/dev/null
        else
            docker run -d \
                --name "$server_container" \
                --hostname "$server_container" \
                --network "$NETWORK_NAME" \
                --network-alias "$server_container" \
                --cap-add NET_ADMIN \
                -v "$config_dir:/app/MVPORAM/build/install/MVPORAM/config:ro" \
                -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
                "$IMAGE_NAME" \
                bash -lc "sleep $SERVER_START_DELAY_SECONDS && \
                          tc qdisc add dev eth0 root tbf rate $BANDWIDTH burst $BURST latency $LATENCY && \
                          env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" \
                          ./smartrun.sh oram.server.ORAMServer \
                          $MAX_CONCURRENT_CLIENTS $i" \
                >/dev/null
        fi
    done

    verify_servers_running "$replicas" "$run_dir" || {
        append_failed_result "$replicas" "$f" "$clients" "$tag"
        cleanup
        stop_watchdog
        return
    }

    wait_for_all_servers_ready "$replicas" "$run_dir" "Ready to process operations" || {
        dump_server_logs "$replicas" "$run_dir"
        append_failed_result "$replicas" "$f" "$clients" "$tag"
        cleanup
        stop_watchdog
        return
    }

    if [[ "$replicas" != "1" && "$BFT_STABILIZE_SECONDS" -gt 0 ]]; then
        echo "[INFO] Waiting ${BFT_STABILIZE_SECONDS}s for BFT replica connections to stabilize..."
        sleep "$BFT_STABILIZE_SECONDS"
    fi

    CONTAINERS+=("$client_container")
    docker run -d \
        --name "$client_container" \
        --hostname "$client_container" \
        --network "$NETWORK_NAME" \
        --network-alias "$client_container" \
        --cap-add NET_ADMIN \
        -v "$config_dir:/app/MVPORAM/build/install/MVPORAM/config:ro" \
        -e JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
        "$IMAGE_NAME" \
        sleep infinity \
        >/dev/null

    if [[ "$SHAPE_CLIENT" == "1" ]]; then
        docker exec "$client_container" bash -lc "tc qdisc add dev eth0 root tbf rate $BANDWIDTH burst $BURST latency $LATENCY"
    fi

    local class_name
    local args
    if [[ "$replicas" == "1" ]]; then
        class_name="oram.benchmark.SingleServerBenchmarkClient"
        args="100000 $clients $N_REQUESTS $BID_EXPONENT $BUCKET_SIZE $BLOCK_SIZE $ZIPF_PARAMETER mvpms-server0 $SERVER_PORT false"
    else
        class_name="oram.benchmark.MultiServerBenchmarkClient"
        args="100000 $clients $N_REQUESTS $BID_EXPONENT $BUCKET_SIZE $BLOCK_SIZE $ZIPF_PARAMETER false"
    fi

    local -a rx_before tx_before rx_after tx_after rx_delta tx_delta total_delta
    local server_measure_start_ns server_measure_end_ns server_wall_clock_sec
    for ((i = 0; i < replicas; i++)); do
        rx_before[$i]="$(container_stat_bytes "mvpms-server${i}" rx)"
        tx_before[$i]="$(container_stat_bytes "mvpms-server${i}" tx)"
    done
    server_measure_start_ns="$(date +%s%N)"

    set +e
    timeout "$BENCHMARK_TIMEOUT_SECONDS" docker exec "$client_container" bash -lc \
        "env JAVA_OPTS=\"\$JAVA_HEAP_OPTS\" JAVA_TOOL_OPTIONS=\"\$JAVA_HEAP_OPTS\" ./smartrun.sh $class_name $args" \
        > "$client_log" 2>&1
    local rc=$?
    set -e

    server_measure_end_ns="$(date +%s%N)"
    server_wall_clock_sec="$(awk -v start="$server_measure_start_ns" -v end="$server_measure_end_ns" 'BEGIN { printf "%.6f", (end - start) / 1000000000.0 }')"

    dump_server_logs "$replicas" "$run_dir"

    for ((i = 0; i < replicas; i++)); do
        rx_after[$i]="$(container_stat_bytes "mvpms-server${i}" rx)"
        tx_after[$i]="$(container_stat_bytes "mvpms-server${i}" tx)"
        rx_delta[$i]=$((rx_after[$i] - rx_before[$i]))
        tx_delta[$i]=$((tx_after[$i] - tx_before[$i]))
        total_delta[$i]=$((rx_delta[$i] + tx_delta[$i]))
        echo "$tag,$i,${rx_delta[$i]},${tx_delta[$i]},${total_delta[$i]},$server_wall_clock_sec" >> "$SERVER_DETAIL_CSV"
    done

    local server_rx_total=0
    local server_tx_total=0
    local server_network_total=0
    local server_tx_max=0
    for ((i = 0; i < replicas; i++)); do
        server_rx_total=$((server_rx_total + rx_delta[$i]))
        server_tx_total=$((server_tx_total + tx_delta[$i]))
        server_network_total=$((server_network_total + total_delta[$i]))
        if [[ "${tx_delta[$i]}" -gt "$server_tx_max" ]]; then
            server_tx_max="${tx_delta[$i]}"
        fi
    done
    local server_tx_avg
    server_tx_avg="$(awk -v total="$server_tx_total" -v n="$replicas" 'BEGIN { printf "%.3f", total / n }')"

    if [[ "$rc" -ne 0 ]]; then
        echo "[WARN] Benchmark failed rc=$rc: $tag"
        echo "MVPORAM-Multi,$replicas,$f,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,NA,NA,NA,NA,$server_rx_total,$server_tx_total,$server_network_total,$server_tx_avg,$server_tx_max,$server_wall_clock_sec,$server_wall_clock_sec,FAILED,$tag" >> "$RESULT_CSV"
        cleanup
        stop_watchdog
        return
    fi

    local total_ops time_sec throughput latency
    total_ops="$(grep -oE 'Measured ops\[#\]: [0-9]+' "$client_log" | tail -n 1 | awk '{print $3}' || true)"
    time_sec="$(grep -oE 'Wall-clock time\[s\]: [0-9.]+' "$client_log" | tail -n 1 | awk '{print $3}' || true)"
    throughput="$(grep -oE 'Throughput\[ops/s\]: [0-9.]+' "$client_log" | tail -n 1 | awk '{print $2}' || true)"

    total_ops="${total_ops:-NA}"
    time_sec="${time_sec:-NA}"
    throughput="${throughput:-NA}"

    if [[ "$total_ops" == "NA" || "$time_sec" == "NA" || "$throughput" == "NA" ]]; then
        echo "[WARN] Could not parse benchmark output: $tag"
        tail -n 80 "$client_log" || true
        echo "MVPORAM-Multi,$replicas,$f,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,NA,NA,NA,NA,$server_rx_total,$server_tx_total,$server_network_total,$server_tx_avg,$server_tx_max,$server_wall_clock_sec,$server_wall_clock_sec,FAILED,$tag" >> "$RESULT_CSV"
    else
        latency="$(awk -v t="$time_sec" -v n="$N_REQUESTS" 'BEGIN { printf "%.6f", (t * 1000.0) / n }')"
        echo "[RESULT] replicas=$replicas clients=$clients throughput=$throughput ops/s latency=$latency ms serverTxBytes=$server_tx_total"
        echo "MVPORAM-Multi,$replicas,$f,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$total_ops,$time_sec,$throughput,$latency,$server_rx_total,$server_tx_total,$server_network_total,$server_tx_avg,$server_tx_max,$server_wall_clock_sec,$server_wall_clock_sec,OK,$tag" >> "$RESULT_CSV"
    fi

    cleanup
    stop_watchdog
}

for replicas in $REPLICA_VALUES; do
    case "$replicas" in
        1|4|7|10) ;;
        *)
            echo "[ERROR] Unsupported replica count: $replicas. Use 1, 4, 7, or 10."
            exit 1
            ;;
    esac
    for clients in $CLIENT_VALUES; do
        run_one "$replicas" "$clients"
    done
done

echo
echo "[INFO] Results CSV: $RESULT_CSV"
