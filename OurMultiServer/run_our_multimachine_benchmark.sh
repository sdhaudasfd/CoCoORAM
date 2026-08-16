#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="$ROOT_DIR/build/install/OurMultiServer"
CONFIG_DIR="$INSTALL_DIR/config"
LOG_ROOT="$ROOT_DIR/benchmark_logs/real_multimachine"
RESULT_DIR="$ROOT_DIR/benchmark_results"

ROLE="${ROLE:-}"
BUILD_PROJECT="${BUILD_PROJECT:-0}"

REPLICAS="${REPLICAS:-${REPLICA_VALUES:-4}}"
CLIENTS="${CLIENTS:-${CLIENT_VALUES:-1}}"
LOCAL_CLIENTS="${LOCAL_CLIENTS:-$CLIENTS}"
CLIENT_START_ID="${CLIENT_START_ID:-100000}"
N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
ROOT_BUCKET_SIZE="${ROOT_BUCKET_SIZE:-1}"
COMPETITION_BUCKET_SIZE="${COMPETITION_BUCKET_SIZE:-1}"
BUCKET_SIZE="${BUCKET_SIZE:-3}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"

BANDWIDTH="${BANDWIDTH:-10gbit}"
BASE_SERVER_PORT="${BASE_SERVER_PORT:-11000}"
BASE_REPLICA_PORT="${BASE_REPLICA_PORT:-11001}"
BASE_BARRIER_PORT="${BASE_BARRIER_PORT:-12000}"
PORT_STEP="${PORT_STEP:-10}"
SERVER_HOSTS="${SERVER_HOSTS:-}"
CLIENT_HOST="${CLIENT_HOST:-127.0.0.1}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-900}"
SERVER_AUTO_STOP="${SERVER_AUTO_STOP:-1}"
USE_DISTRIBUTED_BARRIER="${USE_DISTRIBUTED_BARRIER:-0}"

SERVER_COUNT="${SERVER_COUNT:-}"
SERVER_START_ID="${SERVER_START_ID:-0}"
SERVER_IDS="${SERVER_IDS:-}"

BANDWIDTH_LABEL="$(echo "$BANDWIDTH" | tr -c '[:alnum:]_-' '_')"
TAG="${TAG:-OurMS_real_n${REPLICAS}_c${CLIENTS}_local${LOCAL_CLIENTS}_N${BID_EXPONENT}_B${BLOCK_SIZE}_${BANDWIDTH_LABEL}}"
RUN_DIR="$LOG_ROOT/$TAG"
RESULT_CSV="${RESULT_CSV:-$RESULT_DIR/OurMultiServer_real_${BANDWIDTH_LABEL}_results.csv}"
SERVER_DETAIL_CSV="${SERVER_DETAIL_CSV:-$RESULT_DIR/OurMultiServer_real_${BANDWIDTH_LABEL}_server_network.csv}"
SERVER_BENCHMARK_CSV="${SERVER_BENCHMARK_CSV:-$RESULT_DIR/OurMultiServer_real_${BANDWIDTH_LABEL}_server_benchmark.csv}"
CLIENT_LATENCY_CSV="${CLIENT_LATENCY_CSV:-$RESULT_DIR/OurMultiServer_real_${BANDWIDTH_LABEL}_client_latency.csv}"
CSV_HEADER="scheme,replicas,f,clients,logN,blockSize,requestsPerClient,bandwidth,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag"
SERVER_DETAIL_HEADER="tag,replica,rxBytes,txBytes,totalBytes,wallClockSec,host,interface"
SERVER_BENCHMARK_HEADER="tag,replica,completedOps,wallClockSec,throughputOpsPerSec,latencyMs,rxBytes,txBytes,totalBytes,interface"
CLIENT_LATENCY_HEADER="tag,clientStartId,localClients,globalClients,requestsPerClient,totalOps,wallClockSec,throughputOpsPerSec,avgTotalMs,avgRound1PhaseMs,avgRound2PhaseMs,avgRound3PhaseMs"

usage() {
    cat <<EOF
Usage:
  ROLE=server|client|config|stop SERVER_HOSTS=<ip[,ip...]> [options] bash $0

Common options:
  REPLICAS=4                 total replicas, e.g., 1, 4, 7, 10
  CLIENTS=50                 global concurrent clients used by the protocol
  N_REQUESTS=1000            requests per local client thread
  BID_EXPONENT=18            log2(N)
  BLOCK_SIZE=4096
  ROOT_BUCKET_SIZE=1 COMPETITION_BUCKET_SIZE=1 BUCKET_SIZE=3
  BANDWIDTH=10gbit           label only in this non-Docker script

Server-role options:
  SERVER_HOSTS="10.0.0.10"             all replicas on one server machine
  SERVER_HOSTS="10.0.0.10,10.0.0.10,10.0.0.11,10.0.0.11"
                                      explicit replica-to-host mapping
  SERVER_COUNT=4 SERVER_START_ID=0     run replica ids 0..3 on this machine
  SERVER_IDS="0 1"                     alternatively, run explicit replica ids

Client-role options:
  LOCAL_CLIENTS=50                     client threads on this physical machine
  CLIENT_START_ID=100000               first BFT client id on this machine

Examples:
  # One server machine runs four replicas.
  ROLE=server REPLICAS=4 SERVER_COUNT=4 SERVER_START_ID=0 \\
    SERVER_HOSTS="10.0.0.10" CLIENTS=50 bash $0

  # One client machine runs all 50 clients.
  ROLE=client REPLICAS=4 SERVER_HOSTS="10.0.0.10" \\
    CLIENTS=50 LOCAL_CLIENTS=50 CLIENT_START_ID=100000 bash $0

  # Split replicas: server A runs ids 0,1 and server B runs ids 2,3.
  ROLE=server REPLICAS=4 SERVER_IDS="0 1" \\
    SERVER_HOSTS="10.0.0.10,10.0.0.10,10.0.0.11,10.0.0.11" CLIENTS=50 bash $0

  ROLE=server REPLICAS=4 SERVER_IDS="2 3" \\
    SERVER_HOSTS="10.0.0.10,10.0.0.10,10.0.0.11,10.0.0.11" CLIENTS=50 bash $0
EOF
}

die() {
    echo "[ERROR] $*" >&2
    exit 1
}

run_gradle() {
    if command -v gradle >/dev/null 2>&1; then
        gradle "$@"
    else
        ./gradlew "$@"
    fi
}

is_number() {
    [[ "$1" =~ ^[0-9]+$ ]]
}

replica_f() {
    echo $(( (REPLICAS - 1) / 3 ))
}

server_host_for() {
    local replica_id="$1"
    IFS=',' read -r -a hosts <<< "$SERVER_HOSTS"
    local n="${#hosts[@]}"
    [[ "$n" -gt 0 && -n "${hosts[0]}" ]] || die "SERVER_HOSTS is required"

    if [[ "$n" -ge "$REPLICAS" ]]; then
        echo "${hosts[$replica_id]}"
    else
        echo "${hosts[$((replica_id % n))]}"
    fi
}

client_port_for() {
    local replica_id="$1"
    echo $((BASE_SERVER_PORT + replica_id * PORT_STEP))
}

replica_port_for() {
    local replica_id="$1"
    echo $((BASE_REPLICA_PORT + replica_id * PORT_STEP))
}

barrier_host() {
    server_host_for 0
}

barrier_port() {
    echo "$BASE_BARRIER_PORT"
}

write_config() {
    local f
    f="$(replica_f)"
    local initial_view
    initial_view="$(seq -s, 0 $((REPLICAS - 1)))"

    mkdir -p "$CONFIG_DIR"

    {
        echo "# Generated by run_our_multimachine_benchmark.sh"
        echo "# server id, address, client-server port, server-server port"
        for ((i = 0; i < REPLICAS; i++)); do
            echo "$i $(server_host_for "$i") $(client_port_for "$i") $(replica_port_for "$i")"
        done
        echo "7001 $CLIENT_HOST 11100"
    } > "$CONFIG_DIR/hosts.config"

    sed -i \
        -e "s/^system.servers.num[[:space:]]*=.*/system.servers.num = $REPLICAS/" \
        -e "s/^system.servers.f[[:space:]]*=.*/system.servers.f = $f/" \
        -e "s/^system.initial.view[[:space:]]*=.*/system.initial.view = $initial_view/" \
        "$CONFIG_DIR/system.config"
}

clean_bft_view_cache() {
    find "$ROOT_DIR" "$INSTALL_DIR" -name currentView -print -delete 2>/dev/null || true
}

ensure_built() {
    if [[ "$BUILD_PROJECT" == "1" ]]; then
        echo "[INFO] Building OurMultiServer"
        (cd "$ROOT_DIR" && run_gradle installDist)
    fi

    [[ -f "$INSTALL_DIR/smartrun.sh" ]] || die "Missing $INSTALL_DIR/smartrun.sh. Run BUILD_PROJECT=1 first."
    chmod +x "$INSTALL_DIR/smartrun.sh" || true
}

server_ids_to_run() {
    if [[ -n "$SERVER_IDS" ]]; then
        echo "$SERVER_IDS"
        return
    fi

    local count="$SERVER_COUNT"
    if [[ -z "$count" ]]; then
        count="$REPLICAS"
    fi
    is_number "$count" || die "SERVER_COUNT must be numeric"
    is_number "$SERVER_START_ID" || die "SERVER_START_ID must be numeric"

    seq "$SERVER_START_ID" $((SERVER_START_ID + count - 1))
}

runs_replica_zero() {
    while read -r replica_id; do
        [[ "$replica_id" == "0" ]] && return 0
    done < <(server_ids_to_run)
    return 1
}

wait_ready() {
    local log_file="$1"
    local pattern="$2"
    local label="$3"

    for ((i = 1; i <= READY_TIMEOUT_SECONDS; i++)); do
        if grep -qi "$pattern" "$log_file" 2>/dev/null; then
            echo "[INFO] $label ready"
            return 0
        fi
        if grep -qiE "Exception|BindException|OutOfMemoryError|Received n-f replies" "$log_file" 2>/dev/null; then
            echo "[WARN] $label log contains an error while waiting:"
            tail -n 40 "$log_file" || true
            return 1
        fi
        sleep 1
    done

    echo "[WARN] $label not ready after ${READY_TIMEOUT_SECONDS}s; check $log_file"
    return 1
}

refresh_derived_paths() {
    BANDWIDTH_LABEL="$(echo "$BANDWIDTH" | tr -c '[:alnum:]_-' '_')"
    RUN_DIR="$LOG_ROOT/$TAG"
    RESULT_CSV="${RESULT_DIR}/OurMultiServer_real_${BANDWIDTH_LABEL}_results.csv"
    SERVER_DETAIL_CSV="${RESULT_DIR}/OurMultiServer_real_${BANDWIDTH_LABEL}_server_network.csv"
    SERVER_BENCHMARK_CSV="${RESULT_DIR}/OurMultiServer_real_${BANDWIDTH_LABEL}_server_benchmark.csv"
    CLIENT_LATENCY_CSV="${RESULT_DIR}/OurMultiServer_real_${BANDWIDTH_LABEL}_client_latency.csv"
}

write_run_metadata() {
    local meta_file="$RUN_DIR/run.meta"
    {
        printf 'REPLICAS=%q\n' "$REPLICAS"
        printf 'CLIENTS=%q\n' "$CLIENTS"
        printf 'LOCAL_CLIENTS=%q\n' "$LOCAL_CLIENTS"
        printf 'CLIENT_START_ID=%q\n' "$CLIENT_START_ID"
        printf 'N_REQUESTS=%q\n' "$N_REQUESTS"
        printf 'BID_EXPONENT=%q\n' "$BID_EXPONENT"
        printf 'ROOT_BUCKET_SIZE=%q\n' "$ROOT_BUCKET_SIZE"
        printf 'COMPETITION_BUCKET_SIZE=%q\n' "$COMPETITION_BUCKET_SIZE"
        printf 'BUCKET_SIZE=%q\n' "$BUCKET_SIZE"
        printf 'BLOCK_SIZE=%q\n' "$BLOCK_SIZE"
        printf 'BANDWIDTH=%q\n' "$BANDWIDTH"
        printf 'BASE_SERVER_PORT=%q\n' "$BASE_SERVER_PORT"
        printf 'BASE_REPLICA_PORT=%q\n' "$BASE_REPLICA_PORT"
        printf 'BASE_BARRIER_PORT=%q\n' "$BASE_BARRIER_PORT"
        printf 'PORT_STEP=%q\n' "$PORT_STEP"
        printf 'SERVER_HOSTS=%q\n' "$SERVER_HOSTS"
        printf 'CLIENT_HOST=%q\n' "$CLIENT_HOST"
        printf 'SERVER_COUNT=%q\n' "${SERVER_COUNT:-}"
        printf 'SERVER_START_ID=%q\n' "$SERVER_START_ID"
        printf 'SERVER_IDS=%q\n' "$SERVER_IDS"
        printf 'USE_DISTRIBUTED_BARRIER=%q\n' "$USE_DISTRIBUTED_BARRIER"
        printf 'TAG=%q\n' "$TAG"
    } > "$meta_file"
}

load_run_metadata() {
    local meta_file="$RUN_DIR/run.meta"
    if [[ -f "$meta_file" ]]; then
        # shellcheck disable=SC1090
        source "$meta_file"
        refresh_derived_paths
        echo "[INFO] Loaded run metadata: $meta_file"
    else
        echo "[WARN] No run metadata found: $meta_file"
    fi
}

all_local_servers_reported() {
    local replica_id log_file line
    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        log_file="$RUN_DIR/server${replica_id}.log"
        line="$(grep -- "-- SERVER_BENCHMARK completedOps=" "$log_file" 2>/dev/null | tail -n 1 || true)"
        [[ -n "$line" ]] || return 1
    done < <(server_ids_to_run)
    return 0
}

wait_for_server_benchmarks() {
    echo "[INFO] Waiting for local server benchmark completion..."
    while true; do
        if all_local_servers_reported; then
            echo "[INFO] Local server benchmark completed."
            return 0
        fi
        sleep 2
    done
}

terminate_servers() {
    shopt -s nullglob
    for pid_file in "$RUN_DIR"/barrier.pid; do
        [[ -f "$pid_file" ]] || continue
        local pid
        pid="$(cat "$pid_file")"
        if kill -0 "$pid" >/dev/null 2>&1; then
            echo "[INFO] Stopping distributed barrier pid=$pid from $pid_file"
            kill "$pid" || true
        fi
    done

    for pid_file in "$RUN_DIR"/server*.pid; do
        local pid
        pid="$(cat "$pid_file")"
        if kill -0 "$pid" >/dev/null 2>&1; then
            echo "[INFO] Stopping pid=$pid from $pid_file"
            kill "$pid" || true
        fi
    done

    sleep 1

    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        local client_port replica_port
        client_port="$(client_port_for "$replica_id")"
        replica_port="$(replica_port_for "$replica_id")"

        for port in "$client_port" "$replica_port"; do
            local pids
            pids="$(ss -ltnp "sport = :$port" 2>/dev/null | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u || true)"
            for pid in $pids; do
                if kill -0 "$pid" >/dev/null 2>&1; then
                    echo "[INFO] Stopping listener pid=$pid on port=$port"
                    kill "$pid" || true
                fi
            done
        done
    done < <(server_ids_to_run)

    sleep 1

    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        local client_port replica_port
        client_port="$(client_port_for "$replica_id")"
        replica_port="$(replica_port_for "$replica_id")"

        for port in "$client_port" "$replica_port"; do
            local pids
            pids="$(ss -ltnp "sport = :$port" 2>/dev/null | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u || true)"
            for pid in $pids; do
                if kill -0 "$pid" >/dev/null 2>&1; then
                    echo "[WARN] Force stopping listener pid=$pid on port=$port"
                    kill -9 "$pid" || true
                fi
            done
        done
    done < <(server_ids_to_run)
}

start_distributed_barrier_if_needed() {
    if [[ "$USE_DISTRIBUTED_BARRIER" != "1" ]]; then
        return
    fi
    if [[ "$REPLICAS" == "1" ]]; then
        return
    fi
    if ! runs_replica_zero; then
        return
    fi

    local log_file="$RUN_DIR/barrier.log"
    local pid_file="$RUN_DIR/barrier.pid"
    (
        cd "$INSTALL_DIR"
        nohup env JAVA_OPTS="$JAVA_HEAP_OPTS" JAVA_TOOL_OPTIONS="$JAVA_HEAP_OPTS" \
            ./smartrun.sh oram.benchmark.DistributedBarrierServer \
            "$(barrier_port)" "$CLIENTS" "$((N_REQUESTS * 2))" \
            > "$log_file" 2>&1 &
        echo $! > "$pid_file"
    )
    echo "[INFO] Started distributed barrier pid=$(cat "$pid_file") host=$(barrier_host) port=$(barrier_port) parties=$CLIENTS phases=$((N_REQUESTS * 2))"
    wait_ready "$log_file" "Distributed barrier ready" "distributed barrier" || true
}

run_servers() {
    mkdir -p "$RUN_DIR"
    clean_bft_view_cache
    write_config
    write_run_metadata
    local expected_ops=$((CLIENTS * N_REQUESTS))

    echo "[INFO] Starting server role"
    echo "[INFO] tag=$TAG"
    echo "[INFO] replicas=$REPLICAS, f=$(replica_f), global clients=$CLIENTS"
    echo "[INFO] logs=$RUN_DIR"

    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        is_number "$replica_id" || die "Invalid replica id: $replica_id"
        [[ "$replica_id" -ge 0 && "$replica_id" -lt "$REPLICAS" ]] || die "Replica id $replica_id outside [0,$((REPLICAS - 1))]"

        local log_file="$RUN_DIR/server${replica_id}.log"
        local pid_file="$RUN_DIR/server${replica_id}.pid"

        if [[ "$REPLICAS" == "1" ]]; then
            (
                cd "$INSTALL_DIR"
                nohup env JAVA_OPTS="$JAVA_HEAP_OPTS" JAVA_TOOL_OPTIONS="$JAVA_HEAP_OPTS" SERVER_EXPECTED_OPS="$expected_ops" \
                    ./smartrun.sh oram.server.ORAMServer \
                    "$CLIENTS" "$BID_EXPONENT" "$ROOT_BUCKET_SIZE" "$COMPETITION_BUCKET_SIZE" \
                    "$BUCKET_SIZE" "$BLOCK_SIZE" 0.0.0.0 "$(client_port_for 0)" \
                    > "$log_file" 2>&1 &
                echo $! > "$pid_file"
            )
            echo "[INFO] Started single ORAM server pid=$(cat "$pid_file") port=$(client_port_for 0)"
        else
            (
                cd "$INSTALL_DIR"
                nohup env JAVA_OPTS="$JAVA_HEAP_OPTS" JAVA_TOOL_OPTIONS="$JAVA_HEAP_OPTS" SERVER_EXPECTED_OPS="$expected_ops" \
                    ./smartrun.sh oram.server.BFTORAMServer \
                    "$replica_id" "$CLIENTS" "$BID_EXPONENT" "$ROOT_BUCKET_SIZE" \
                    "$COMPETITION_BUCKET_SIZE" "$BUCKET_SIZE" "$BLOCK_SIZE" \
                    > "$log_file" 2>&1 &
                echo $! > "$pid_file"
            )
            echo "[INFO] Started replica $replica_id pid=$(cat "$pid_file") host=$(server_host_for "$replica_id") ports=$(client_port_for "$replica_id")/$(replica_port_for "$replica_id")"
        fi
    done < <(server_ids_to_run)

    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        local log_file="$RUN_DIR/server${replica_id}.log"
        if [[ "$REPLICAS" == "1" ]]; then
            wait_ready "$log_file" "ORAM server ready" "server $replica_id" || true
        else
            wait_ready "$log_file" "Ready to process operations" "replica $replica_id" || true
        fi
    done < <(server_ids_to_run)

    start_distributed_barrier_if_needed

    if [[ "$SERVER_AUTO_STOP" == "1" ]]; then
        echo "[INFO] Server role will auto-stop after completedOps reaches $expected_ops."
        wait_for_server_benchmarks
        append_server_benchmark_stats
        terminate_servers
        echo "[INFO] Server role finished and stopped local servers."
    else
        echo "[INFO] Server role finished startup. Leave this machine running."
        echo "[INFO] Stop later with: ROLE=stop TAG=$TAG bash $0"
    fi
}

append_header_if_needed() {
    mkdir -p "$RESULT_DIR"
    if [[ ! -f "$RESULT_CSV" ]]; then
        echo "$CSV_HEADER" > "$RESULT_CSV"
    fi
}

append_server_detail_header_if_needed() {
    mkdir -p "$RESULT_DIR"
    if [[ ! -f "$SERVER_DETAIL_CSV" ]]; then
        echo "$SERVER_DETAIL_HEADER" > "$SERVER_DETAIL_CSV"
    fi
}

append_server_benchmark_header_if_needed() {
    mkdir -p "$RESULT_DIR"
    if [[ ! -f "$SERVER_BENCHMARK_CSV" ]]; then
        echo "$SERVER_BENCHMARK_HEADER" > "$SERVER_BENCHMARK_CSV"
    fi
}

append_client_latency_header_if_needed() {
    mkdir -p "$RESULT_DIR"
    if [[ ! -f "$CLIENT_LATENCY_CSV" ]]; then
        echo "$CLIENT_LATENCY_HEADER" > "$CLIENT_LATENCY_CSV"
    fi
}

network_iface() {
    if [[ -n "${NET_IFACE:-}" ]]; then
        echo "$NET_IFACE"
        return
    fi

    local iface
    iface="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "dev") {print $(i + 1); exit}}')"
    if [[ -z "$iface" ]]; then
        iface="$(ip route show default 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "dev") {print $(i + 1); exit}}')"
    fi
    [[ -n "$iface" ]] || die "Could not detect network interface. Set NET_IFACE=eth0 or NET_IFACE=ens5."
    echo "$iface"
}

iface_stat_bytes() {
    local iface="$1"
    local direction="$2"
    local stat_file="/sys/class/net/$iface/statistics/${direction}_bytes"
    [[ -r "$stat_file" ]] || die "Cannot read $stat_file"
    cat "$stat_file"
}

local_server_ids_label() {
    local ids=""
    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        if [[ -z "$ids" ]]; then
            ids="$replica_id"
        else
            ids="${ids};${replica_id}"
        fi
    done < <(server_ids_to_run)
    echo "$ids"
}

local_server_client_ports_label() {
    local ports=""
    while read -r replica_id; do
        [[ -n "$replica_id" ]] || continue
        local port
        port="$(client_port_for "$replica_id")"
        if [[ -z "$ports" ]]; then
            ports="$port"
        else
            ports="${ports};${port}"
        fi
    done < <(server_ids_to_run)
    echo "$ports"
}

start_server_network_watcher() {
    mkdir -p "$RUN_DIR"
    rm -f "$RUN_DIR/server_network.start"

    local iface ids ports host watcher_script watcher_pid_file
    iface="$(network_iface)"
    ids="$(local_server_ids_label)"
    ports="$(local_server_client_ports_label)"
    host="$(hostname)"
    watcher_script="$RUN_DIR/server_network_watcher.sh"
    watcher_pid_file="$RUN_DIR/server_network_watcher.pid"

    cat > "$watcher_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

RUN_DIR="$RUN_DIR"
IFACE="$iface"
SERVER_IDS="$ids"
CLIENT_PORTS="$ports"
HOST_NAME="$host"
START_FILE="\$RUN_DIR/server_network.start"

while true; do
    if [[ -f "\$START_FILE" ]]; then
        exit 0
    fi

    connected=0
    IFS=';' read -r -a ports <<< "\$CLIENT_PORTS"
    for port in "\${ports[@]}"; do
        if ss -tn "sport = :\$port" 2>/dev/null | awk 'NR > 1 {found = 1} END {exit found ? 0 : 1}'; then
            connected=1
            break
        fi
    done

    if [[ "\$connected" == "1" ]]; then
        rx="\$(cat "/sys/class/net/\$IFACE/statistics/rx_bytes")"
        tx="\$(cat "/sys/class/net/\$IFACE/statistics/tx_bytes")"
        start_ns="\$(date +%s%N)"
        {
            echo "iface=\$IFACE"
            echo "rx=\$rx"
            echo "tx=\$tx"
            echo "startNs=\$start_ns"
            echo "serverIds=\$SERVER_IDS"
            echo "host=\$HOST_NAME"
        } > "\$START_FILE.tmp"
        mv "\$START_FILE.tmp" "\$START_FILE"
        echo "[INFO] Server network accounting started on first established client connection: iface=\$IFACE ports=\$CLIENT_PORTS rx=\$rx tx=\$tx"
        exit 0
    fi

    sleep 0.1
done
EOF

    chmod +x "$watcher_script" || true
    nohup bash "$watcher_script" > "$RUN_DIR/server_network_watcher.log" 2>&1 &
    echo $! > "$watcher_pid_file"

    echo "[INFO] Server network watcher started: pid=$(cat "$watcher_pid_file") iface=$iface"
}

finalize_server_network_stats() {
    local start_file="$RUN_DIR/server_network.start"
    for ((i = 0; i < 30; i++)); do
        [[ -f "$start_file" ]] && break
        sleep 0.1
    done

    if [[ ! -f "$start_file" ]]; then
        echo "[WARN] No server network start file: $start_file"
        return
    fi

    append_server_detail_header_if_needed

    local iface rx_before tx_before start_ns ids host rx_after tx_after end_ns
    iface="$(grep '^iface=' "$start_file" | cut -d= -f2-)"
    rx_before="$(grep '^rx=' "$start_file" | cut -d= -f2-)"
    tx_before="$(grep '^tx=' "$start_file" | cut -d= -f2-)"
    start_ns="$(grep '^startNs=' "$start_file" | cut -d= -f2-)"
    ids="$(grep '^serverIds=' "$start_file" | cut -d= -f2-)"
    host="$(grep '^host=' "$start_file" | cut -d= -f2-)"

    rx_after="$(iface_stat_bytes "$iface" rx)"
    tx_after="$(iface_stat_bytes "$iface" tx)"
    end_ns="$(date +%s%N)"

    local rx_delta tx_delta total_delta wall_clock_sec
    rx_delta=$((rx_after - rx_before))
    tx_delta=$((tx_after - tx_before))
    total_delta=$((rx_delta + tx_delta))
    wall_clock_sec="$(awk -v start="$start_ns" -v end="$end_ns" 'BEGIN { printf "%.6f", (end - start) / 1000000000.0 }')"

    echo "$TAG,$ids,$rx_delta,$tx_delta,$total_delta,$wall_clock_sec,$host,$iface" >> "$SERVER_DETAIL_CSV"
    echo "[SERVER-NET] tag=$TAG replica=$ids rxBytes=$rx_delta txBytes=$tx_delta totalBytes=$total_delta wallClockSec=$wall_clock_sec host=$host iface=$iface"
    echo "[INFO] Server network CSV: $SERVER_DETAIL_CSV"
}

extract_kv() {
    local line="$1"
    local key="$2"
    echo "$line" | sed -n "s/.*${key}=\\([^ ]*\\).*/\\1/p"
}

append_server_benchmark_stats() {
    append_header_if_needed
    append_server_benchmark_header_if_needed

    shopt -s nullglob
    local wrote=0
    local should_write_main=0
    local main_completed=""
    local main_wall=""
    for log_file in "$RUN_DIR"/server*.log; do
        local replica line completed wall throughput latency rx tx total iface
        replica="$(basename "$log_file" | sed -n 's/server\([0-9][0-9]*\)\.log/\1/p')"
        line="$(grep -- "-- SERVER_BENCHMARK completedOps=" "$log_file" | tail -n 1 || true)"
        if [[ -z "$line" ]]; then
            echo "[WARN] No SERVER_BENCHMARK line found in $log_file"
            continue
        fi

        completed="$(extract_kv "$line" "completedOps")"
        wall="$(extract_kv "$line" "wallClockSec")"
        throughput="$(extract_kv "$line" "throughputOpsPerSec")"
        rx="$(extract_kv "$line" "rxBytes")"
        tx="$(extract_kv "$line" "txBytes")"
        total="$(extract_kv "$line" "totalBytes")"
        iface="$(extract_kv "$line" "iface")"

        if [[ -n "$completed" && -n "$wall" && "$completed" != "0" ]]; then
            latency="$(awk -v t="$wall" -v ops="$completed" 'BEGIN { printf "%.6f", (t / ops) * 1000.0 }')"
        else
            latency="NA"
        fi

        echo "$TAG,$replica,$completed,$wall,$throughput,$latency,$rx,$tx,$total,$iface" >> "$SERVER_BENCHMARK_CSV"
        echo "[SERVER-BENCHMARK] tag=$TAG replica=$replica completedOps=$completed wallClockSec=$wall throughputOpsPerSec=$throughput latencyMs=$latency rxBytes=$rx txBytes=$tx totalBytes=$total iface=$iface"
        if [[ "$replica" == "0" ]]; then
            should_write_main=1
        fi
        if [[ -z "$main_completed" || "$completed" -gt "$main_completed" ]]; then
            main_completed="$completed"
        fi
        if [[ -z "$main_wall" || "$(awk -v a="$wall" -v b="$main_wall" 'BEGIN { print (a > b) ? 1 : 0 }')" == "1" ]]; then
            main_wall="$wall"
        fi
        wrote=1
    done

    if [[ "$wrote" == "1" ]]; then
        if [[ "$should_write_main" == "1" && -n "$main_completed" && -n "$main_wall" && "$main_completed" != "0" ]]; then
            local main_throughput main_latency
            main_throughput="$(awk -v ops="$main_completed" -v t="$main_wall" 'BEGIN { printf "%.3f", ops / t }')"
            main_latency="$(awk -v ops="$main_completed" -v t="$main_wall" 'BEGIN { printf "%.6f", (t / ops) * 1000.0 }')"
            echo "C2ORAM-Multi,$REPLICAS,$(replica_f),$CLIENTS,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$main_completed,$main_wall,$main_throughput,$main_latency,OK,$TAG" >> "$RESULT_CSV"
        fi
        echo "[INFO] Server result CSV: $RESULT_CSV"
        echo "[INFO] Server benchmark CSV: $SERVER_BENCHMARK_CSV"
    fi
}

extract_metric() {
    local label="$1"
    local log_file="$2"
    grep -E "$label" "$log_file" | tail -n 1 | awk -F: '{gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2}'
}

extract_timing_metric() {
    local key="$1"
    local log_file="$2"
    local line
    line="$(grep -- "-- access timing summary" "$log_file" | tail -n 1 || true)"
    if [[ -z "$line" ]]; then
        echo "NA"
        return
    fi
    local value
    value="$(echo "$line" | sed -n "s/.*${key}=\\([^ ]*\\).*/\\1/p")"
    if [[ -z "$value" ]]; then
        echo "NA"
    else
        echo "$value"
    fi
}

append_client_latency_stats() {
    local log_file="$1"
    append_client_latency_header_if_needed

    local total_ops wall_clock throughput avg_total avg_r1 avg_r2 avg_r3
    total_ops="$(extract_metric "Measured ops\\[#\\]" "$log_file")"
    wall_clock="$(extract_metric "Wall-clock time\\[s\\]" "$log_file")"
    throughput="$(extract_metric "Throughput\\[ops/s\\]" "$log_file")"
    avg_total="$(extract_timing_metric "avgTotalMs" "$log_file")"
    avg_r1="$(extract_timing_metric "avgRound1PhaseMs" "$log_file")"
    avg_r2="$(extract_timing_metric "avgRound2PhaseMs" "$log_file")"
    avg_r3="$(extract_timing_metric "avgRound3PhaseMs" "$log_file")"

    if [[ -z "$total_ops" || -z "$wall_clock" || -z "$throughput" ]]; then
        echo "[WARN] Could not parse client latency summary from $log_file"
        return
    fi

    echo "$TAG,$CLIENT_START_ID,$LOCAL_CLIENTS,$CLIENTS,$N_REQUESTS,$total_ops,$wall_clock,$throughput,$avg_total,$avg_r1,$avg_r2,$avg_r3" >> "$CLIENT_LATENCY_CSV"
    echo "[INFO] Client latency CSV: $CLIENT_LATENCY_CSV"
}

run_client() {
    mkdir -p "$RUN_DIR"
    clean_bft_view_cache
    write_config

    local f
    f="$(replica_f)"
    local log_file="$RUN_DIR/client_${CLIENT_START_ID}_${LOCAL_CLIENTS}.log"

    echo "[INFO] Starting client role"
    echo "[INFO] tag=$TAG"
    echo "[INFO] replicas=$REPLICAS, f=$f, global clients=$CLIENTS, local clients=$LOCAL_CLIENTS"
    echo "[INFO] clientStartId=$CLIENT_START_ID"
    echo "[INFO] log=$log_file"

    local barrier_opts=""
    if [[ "$REPLICAS" != "1" && "$USE_DISTRIBUTED_BARRIER" == "1" ]]; then
        barrier_opts="-Doram.distributedBarrierHost=$(barrier_host) -Doram.distributedBarrierPort=$(barrier_port)"
        echo "[INFO] distributedBarrier=$(barrier_host):$(barrier_port)"
    fi

    set +e
    if [[ "$REPLICAS" == "1" ]]; then
        (
            cd "$INSTALL_DIR"
            env JAVA_OPTS="$JAVA_HEAP_OPTS" JAVA_TOOL_OPTIONS="$JAVA_HEAP_OPTS" \
                ./smartrun.sh oram.benchmark.OursBenchmark \
                "$N_REQUESTS" "$LOCAL_CLIENTS" "$BID_EXPONENT" "$ROOT_BUCKET_SIZE" \
                "$COMPETITION_BUCKET_SIZE" "$BUCKET_SIZE" "$BLOCK_SIZE" \
                "$(server_host_for 0)" "$(client_port_for 0)" "$CLIENT_START_ID" "$CLIENTS"
        ) > "$log_file" 2>&1
    else
        (
            cd "$INSTALL_DIR"
            env JAVA_OPTS="$JAVA_HEAP_OPTS $barrier_opts" JAVA_TOOL_OPTIONS="$JAVA_HEAP_OPTS $barrier_opts" \
                ./smartrun.sh oram.benchmark.OursBenchmark \
                "$N_REQUESTS" "$LOCAL_CLIENTS" "$BID_EXPONENT" "$ROOT_BUCKET_SIZE" \
                "$COMPETITION_BUCKET_SIZE" "$BUCKET_SIZE" "$BLOCK_SIZE" \
                "$CLIENT_START_ID" "$CLIENTS"
        ) > "$log_file" 2>&1
    fi
    local rc=$?
    set -e

    if [[ "$rc" == "0" ]]; then
        append_client_latency_stats "$log_file"
        echo "[INFO] Client finished; official result is written by server role."
    else
        echo "[WARN] Client failed rc=$rc; see $log_file"
        tail -n 80 "$log_file" || true
        return "$rc"
    fi
}

stop_servers() {
    local dir="$RUN_DIR"
    [[ -d "$dir" ]] || die "No run dir: $dir"

    load_run_metadata
    append_server_benchmark_stats
    terminate_servers
}

main() {
    [[ -n "$ROLE" ]] || { usage; die "ROLE is required"; }
    [[ -n "$SERVER_HOSTS" || "$ROLE" == "stop" ]] || { usage; die "SERVER_HOSTS is required"; }

    ensure_built

    case "$ROLE" in
        config)
            write_config
            echo "[INFO] Wrote $CONFIG_DIR/hosts.config and updated system.config"
            ;;
        server)
            run_servers
            ;;
        client)
            run_client
            ;;
        stop)
            stop_servers
            ;;
        *)
            usage
            die "Unknown ROLE: $ROLE"
            ;;
    esac
}

main "$@"
