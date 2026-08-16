#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
CLIENT_MACHINE_INDEX="${CLIENT_MACHINE_INDEX:?Set CLIENT_MACHINE_INDEX, e.g., 0 for one large client machine}"
CLIENT_MACHINE_COUNT="${CLIENT_MACHINE_COUNT:-1}"
FIRST_CLIENT_ID="${FIRST_CLIENT_ID:-100000}"
BASE_SERVER_PORT="${BASE_SERVER_PORT:-11000}"
PORT_STEP="${PORT_STEP:-10}"
PORT_WAIT_SECONDS="${PORT_WAIT_SECONDS:-3600}"
CLEAN_PAUSE_SECONDS="${CLEAN_PAUSE_SECONDS:-10}"
CLIENT_START_DELAY_SECONDS="${CLIENT_START_DELAY_SECONDS:-30}"
GROUP_PAUSE_SECONDS="${GROUP_PAUSE_SECONDS:-10}"
WAIT_FOR_SERVER_CLOSE="${WAIT_FOR_SERVER_CLOSE:-0}"

server_host_for() {
    local replica_id="$1"
    local hosts="${SERVER_HOSTS:?Set SERVER_HOSTS}"
    IFS=',' read -r -a host_array <<< "$hosts"
    local n="${#host_array[@]}"
    if [[ "$n" -ge "${REPLICAS:-1}" ]]; then
        echo "${host_array[$replica_id]}"
    else
        echo "${host_array[$((replica_id % n))]}"
    fi
}

client_port_for() {
    local replica_id="$1"
    echo $((BASE_SERVER_PORT + replica_id * PORT_STEP))
}

port_open() {
    local host="$1"
    local port="$2"
    if command -v nc >/dev/null 2>&1; then
        nc -z -w 1 "$host" "$port" >/dev/null 2>&1
    else
        timeout 1 bash -c "cat < /dev/null > /dev/tcp/$host/$port" >/dev/null 2>&1
    fi
}

wait_for_port_open() {
    local host="$1"
    local port="$2"
    local label="$3"
    for ((i = 0; i < PORT_WAIT_SECONDS; i++)); do
        if port_open "$host" "$port"; then
            echo "[CLEAN-SUITE] $label is open: $host:$port"
            return 0
        fi
        sleep 1
    done
    echo "[ERROR] Timed out waiting for $label to open: $host:$port" >&2
    exit 1
}

wait_for_port_closed() {
    local host="$1"
    local port="$2"
    local label="$3"
    for ((i = 0; i < PORT_WAIT_SECONDS; i++)); do
        if ! port_open "$host" "$port"; then
            echo "[CLEAN-SUITE] $label is closed: $host:$port"
            return 0
        fi
        sleep 1
    done
    echo "[ERROR] Timed out waiting for $label to close: $host:$port" >&2
    exit 1
}

wait_for_all_replicas_open() {
    local label="$1"
    local replicas="${REPLICAS:-1}"
    for ((replica = 0; replica < replicas; replica++)); do
        wait_for_port_open "$(server_host_for "$replica")" "$(client_port_for "$replica")" "$label replica=$replica"
    done
}

wait_for_all_replicas_closed() {
    local label="$1"
    local replicas="${REPLICAS:-1}"
    for ((replica = 0; replica < replicas; replica++)); do
        wait_for_port_closed "$(server_host_for "$replica")" "$(client_port_for "$replica")" "$label replica=$replica"
    done
}

local_clients_for() {
    local total="$1"
    local index="$2"
    if [[ "$CLIENT_MACHINE_COUNT" -eq 1 ]]; then
        [[ "$index" -eq 0 ]] && echo "$total" || echo 0
        return
    fi
    local base=$((total / CLIENT_MACHINE_COUNT))
    local rem=$((total % CLIENT_MACHINE_COUNT))
    if [[ "$index" -lt "$rem" ]]; then
        echo $((base + 1))
    elif [[ "$index" -lt "$CLIENT_MACHINE_COUNT" ]]; then
        echo "$base"
    else
        echo 0
    fi
}

client_start_id_for() {
    local total="$1"
    local index="$2"
    local offset=0
    for ((i = 0; i < index; i++)); do
        offset=$((offset + $(local_clients_for "$total" "$i")))
    done
    echo $((FIRST_CLIENT_ID + offset))
}

cleanup_local_mvp_clients() {
    pkill -f 'oram\.benchmark\.MultiServerBenchmarkClient|oram\.benchmark\.SingleServerBenchmarkClient' 2>/dev/null || true
    sleep 1
    pkill -9 -f 'oram\.benchmark\.MultiServerBenchmarkClient|oram\.benchmark\.SingleServerBenchmarkClient' 2>/dev/null || true
    sleep "$CLEAN_PAUSE_SECONDS"
}

for clients in $CLIENT_VALUES; do
    local_clients="$(local_clients_for "$clients" "$CLIENT_MACHINE_INDEX")"
    client_start_id="$(client_start_id_for "$clients" "$CLIENT_MACHINE_INDEX")"

    echo
    echo "============================================================"
    echo "[CLEAN-SUITE] MVP client group: clients=$clients machine=$CLIENT_MACHINE_INDEX/$CLIENT_MACHINE_COUNT localClients=$local_clients startId=$client_start_id"
    echo "============================================================"

    cleanup_local_mvp_clients
    wait_for_all_replicas_open "server group clients=$clients"
    echo "[CLEAN-SUITE] waiting ${CLIENT_START_DELAY_SECONDS}s for BFT connections to settle"
    sleep "$CLIENT_START_DELAY_SECONDS"

    if [[ "$local_clients" -gt 0 ]]; then
        ROLE=client \
        CLIENTS="$clients" \
        LOCAL_CLIENTS="$local_clients" \
        CLIENT_START_ID="$client_start_id" \
        bash "$ROOT_DIR/run_mvp_multimachine_benchmark.sh"
    else
        echo "[CLEAN-SUITE] This machine has 0 clients for clients=$clients; waiting for group to finish."
    fi

    if [[ "$WAIT_FOR_SERVER_CLOSE" == "1" ]]; then
        wait_for_all_replicas_closed "server group clients=$clients"
    else
        echo "[CLEAN-SUITE] Not waiting for server ports to close; server suite may already have opened the next group."
    fi
    cleanup_local_mvp_clients
    sleep "$GROUP_PAUSE_SECONDS"
done

echo "[CLEAN-SUITE] All MVP client groups finished on machine $CLIENT_MACHINE_INDEX."
