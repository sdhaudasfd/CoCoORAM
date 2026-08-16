#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
CLIENT_MACHINE_INDEX="${CLIENT_MACHINE_INDEX:?Set CLIENT_MACHINE_INDEX, e.g., 0..5 for the six client machines}"
CLIENT_MACHINE_COUNT="${CLIENT_MACHINE_COUNT:-6}"
FIRST_CLIENT_ID="${FIRST_CLIENT_ID:-100000}"
BASE_SERVER_PORT="${BASE_SERVER_PORT:-11000}"
PORT_STEP="${PORT_STEP:-10}"
PORT_WAIT_SECONDS="${PORT_WAIT_SECONDS:-3600}"
GROUP_PAUSE_SECONDS="${GROUP_PAUSE_SECONDS:-1}"
CLIENT_START_DELAY_SECONDS="${CLIENT_START_DELAY_SECONDS:-10}"

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
            echo "[SUITE] $label is open: $host:$port"
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
            echo "[SUITE] $label is closed: $host:$port"
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
    case "$total" in
        1)
            [[ "$index" -eq 0 ]] && echo 1 || echo 0
            ;;
        5)
            [[ "$index" -eq 0 ]] && echo 5 || echo 0
            ;;
        10)
            [[ "$index" -lt 2 ]] && echo 5 || echo 0
            ;;
        15)
            [[ "$index" -lt 3 ]] && echo 5 || echo 0
            ;;
        20)
            [[ "$index" -lt 4 ]] && echo 5 || echo 0
            ;;
        30)
            [[ "$index" -lt 5 ]] && echo 6 || echo 0
            ;;
        40)
            [[ "$index" -lt 5 ]] && echo 8 || echo 0
            ;;
        50)
            if [[ "$index" -lt 2 ]]; then
                echo 9
            elif [[ "$index" -lt 6 ]]; then
                echo 8
            else
                echo 0
            fi
            ;;
        *)
            local base=$((total / CLIENT_MACHINE_COUNT))
            local rem=$((total % CLIENT_MACHINE_COUNT))
            if [[ "$index" -lt "$rem" ]]; then
                echo $((base + 1))
            elif [[ "$index" -lt "$CLIENT_MACHINE_COUNT" ]]; then
                echo "$base"
            else
                echo 0
            fi
            ;;
    esac
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

for clients in $CLIENT_VALUES; do
    local_clients="$(local_clients_for "$clients" "$CLIENT_MACHINE_INDEX")"
    client_start_id="$(client_start_id_for "$clients" "$CLIENT_MACHINE_INDEX")"

    echo
    echo "============================================================"
    echo "[SUITE] C2ORAM client group: clients=$clients machine=$CLIENT_MACHINE_INDEX/$CLIENT_MACHINE_COUNT localClients=$local_clients startId=$client_start_id"
    echo "============================================================"

    wait_for_all_replicas_open "server group clients=$clients"
    if [[ "$CLIENT_START_DELAY_SECONDS" != "0" ]]; then
        echo "[SUITE] Waiting ${CLIENT_START_DELAY_SECONDS}s for BFT replicas to stabilize before starting clients=$clients."
        sleep "$CLIENT_START_DELAY_SECONDS"
    fi

    if [[ "$local_clients" -gt 0 ]]; then
        ROLE=client \
        CLIENTS="$clients" \
        LOCAL_CLIENTS="$local_clients" \
        CLIENT_START_ID="$client_start_id" \
        bash "$ROOT_DIR/run_our_multimachine_benchmark.sh"
    else
        echo "[SUITE] This machine has 0 clients for clients=$clients; waiting for group to finish."
    fi

    wait_for_all_replicas_closed "server group clients=$clients"
    sleep "$GROUP_PAUSE_SECONDS"
done

echo "[SUITE] All C2ORAM client groups finished on machine $CLIENT_MACHINE_INDEX."
