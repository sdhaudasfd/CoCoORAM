#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$ROOT_DIR/wan_results"

BANDWIDTH="${BANDWIDTH:-10gbit}"
BANDWIDTH_LABEL="${BANDWIDTH_LABEL:-${BANDWIDTH//[^A-Za-z0-9_-]/_}}"
LOG_DIR="$OUT_DIR/logs_block_size_${BANDWIDTH_LABEL}"
mkdir -p "$LOG_DIR"

N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
N_CLIENTS="${N_CLIENTS:-50}"
BLOCK_SIZE_VALUES="${BLOCK_SIZE_VALUES:-64 256 1024 4096}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-28800}"
BUILD_PROJECTS="${BUILD_PROJECTS:-0}"
BUILD_IMAGES="${BUILD_IMAGES:-0}"
ZIPF_PARAMETER="${ZIPF_PARAMETER:-0.0}"

OUR_ROOT_BUCKET_SIZE="${OUR_ROOT_BUCKET_SIZE:-1}"
OUR_COMPETITION_BUCKET_SIZE="${OUR_COMPETITION_BUCKET_SIZE:-1}"
OUR_BUCKET_SIZE="${OUR_BUCKET_SIZE:-3}"
MVP_BUCKET_SIZE="${MVP_BUCKET_SIZE:-3}"
MVPSTRONG_ROOT_BUCKET_SIZE="${MVPSTRONG_ROOT_BUCKET_SIZE:-20}"
MVPSTRONG_BUCKET_SIZE="${MVPSTRONG_BUCKET_SIZE:-3}"
CONCUR_STASH_SIZE="${CONCUR_STASH_SIZE:-20}"
CONCUR_Z="${CONCUR_Z:-4}"
CONCUR_S="${CONCUR_S:-3}"
CONCUR_A="${CONCUR_A:-3}"

RESULT_CSV="$OUT_DIR/BlockSize_${BANDWIDTH_LABEL}_results.csv"
SETTING_CSV="$OUT_DIR/BlockSize_${BANDWIDTH_LABEL}_settings.csv"

SCHEME_NAMES=("OurORAM" "MVPORAM" "MVPORAM-Strong" "ConcurORAM")
SCHEME_LABELS=("CoCo-ORAM" "MVP-ORAM" "MVP-ORAM-S" "ConcurORAM")
SCHEME_DIRS=("OurORAM" "MVPORAM" "MVPORAM-Strong" "ConcurORAM")
SCHEME_SCRIPTS=(
    "run_our_docker_bandwidth_benchmark.sh"
    "run_mvp_docker_bandwidth_benchmark.sh"
    "run_mvpstrong_docker_bandwidth_benchmark.sh"
    "run_concur_docker_bandwidth_benchmark.sh"
)

write_headers() {
    cat > "$RESULT_CSV" <<EOF
scheme,clients,logN,blockSize,requestsPerClient,bandwidth,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag
EOF

    cat > "$SETTING_CSV" <<EOF
tag,scheme,schemeDir,script,clients,logN,blockSize,requestsPerClient,bandwidth,zipfParameter,timeoutSeconds,buildProjects,buildImages,ourRootBucketSize,ourCompetitionBucketSize,ourBucketSize,mvpBucketSize,mvpStrongRootBucketSize,mvpStrongBucketSize,concurStashSize,concurZ,concurS,concurA,rootDir
EOF
}

build_projects_if_needed() {
    if [[ "$BUILD_PROJECTS" != "1" ]]; then
        return
    fi

    for dir in "${SCHEME_DIRS[@]}"; do
        echo "[INFO] Building project: $dir"
        (
            cd "$ROOT_DIR/$dir"
            ./gradlew installDist
        )
    done
}

extract_metric() {
    local pattern="$1"
    local field="$2"
    local file="$3"
    grep -oE "$pattern" "$file" 2>/dev/null | tail -n 1 | awk -v f="$field" '{print $f}' || true
}

run_one() {
    local scheme_index="$1"
    local block_size="$2"
    local build_image="$3"

    local scheme_name="${SCHEME_NAMES[$scheme_index]}"
    local scheme_label="${SCHEME_LABELS[$scheme_index]}"
    local scheme_dir="${SCHEME_DIRS[$scheme_index]}"
    local script="${SCHEME_SCRIPTS[$scheme_index]}"
    local tag="${scheme_name}_blockSize_B${block_size}_${BANDWIDTH}"
    local run_dir="$ROOT_DIR/$scheme_dir"
    local scheme_log_dir="$run_dir/benchmark_logs/docker_bandwidth"
    local status="OK"

    echo
    echo "============================================================"
    echo "[INFO] $tag"
    echo "[INFO] bandwidth=$BANDWIDTH, requests=$N_REQUESTS, logN=$BID_EXPONENT, clients=$N_CLIENTS, blockSize=$block_size"
    echo "============================================================"

    if [[ ! -f "$run_dir/$script" ]]; then
        echo "[ERROR] Missing script: $run_dir/$script"
        status="MISSING_SCRIPT"
    else
        chmod +x "$run_dir/$script"
        set +e
        (
            cd "$run_dir"
            case "$scheme_name" in
                OurORAM)
                    timeout "$RUN_TIMEOUT_SECONDS" env \
                        BANDWIDTH="$BANDWIDTH" \
                        N_REQUESTS="$N_REQUESTS" \
                        N_CLIENTS="$N_CLIENTS" \
                        BID_EXPONENT="$BID_EXPONENT" \
                        ROOT_BUCKET_SIZE="$OUR_ROOT_BUCKET_SIZE" \
                        COMPETITION_BUCKET_SIZE="$OUR_COMPETITION_BUCKET_SIZE" \
                        BUCKET_SIZE="$OUR_BUCKET_SIZE" \
                        BLOCK_SIZE="$block_size" \
                        ZIPF_PARAMETER="$ZIPF_PARAMETER" \
                        BUILD_IMAGE="$build_image" \
                        "./$script"
                    ;;
                MVPORAM)
                    timeout "$RUN_TIMEOUT_SECONDS" env \
                        BANDWIDTH="$BANDWIDTH" \
                        N_REQUESTS="$N_REQUESTS" \
                        N_CLIENTS="$N_CLIENTS" \
                        BID_EXPONENT="$BID_EXPONENT" \
                        BUCKET_SIZE="$MVP_BUCKET_SIZE" \
                        BLOCK_SIZE="$block_size" \
                        ZIPF_PARAMETER="$ZIPF_PARAMETER" \
                        BUILD_IMAGE="$build_image" \
                        "./$script"
                    ;;
                MVPORAM-Strong)
                    timeout "$RUN_TIMEOUT_SECONDS" env \
                        BANDWIDTH="$BANDWIDTH" \
                        N_REQUESTS="$N_REQUESTS" \
                        N_CLIENTS="$N_CLIENTS" \
                        BID_EXPONENT="$BID_EXPONENT" \
                        ROOT_BUCKET_SIZE="$MVPSTRONG_ROOT_BUCKET_SIZE" \
                        BUCKET_SIZE="$MVPSTRONG_BUCKET_SIZE" \
                        BLOCK_SIZE="$block_size" \
                        ZIPF_PARAMETER="$ZIPF_PARAMETER" \
                        BUILD_IMAGE="$build_image" \
                        "./$script"
                    ;;
                ConcurORAM)
                    timeout "$RUN_TIMEOUT_SECONDS" env \
                        BANDWIDTH="$BANDWIDTH" \
                        N_REQUESTS="$N_REQUESTS" \
                        N_CLIENTS="$N_CLIENTS" \
                        BID_EXPONENT="$BID_EXPONENT" \
                        STASH_SIZE="$CONCUR_STASH_SIZE" \
                        Z="$CONCUR_Z" \
                        S="$CONCUR_S" \
                        A="$CONCUR_A" \
                        BLOCK_SIZE="$block_size" \
                        ZIPF_PARAMETER="$ZIPF_PARAMETER" \
                        BUILD_IMAGE="$build_image" \
                        "./$script"
                    ;;
            esac
        )
        local exit_code=$?
        set -e

        if [[ "$exit_code" -eq 124 ]]; then
            status="TIMEOUT"
            echo "[WARN] Timeout after ${RUN_TIMEOUT_SECONDS}s: $tag"
        elif [[ "$exit_code" -ne 0 ]]; then
            status="FAILED"
            echo "[WARN] Run failed with exit code $exit_code: $tag"
        fi
    fi

    local client_log="$scheme_log_dir/client.log"
    local server_log="$scheme_log_dir/server.log"
    local iperf_log="$scheme_log_dir/iperf.log"

    [[ -f "$client_log" ]] && cp "$client_log" "$LOG_DIR/${tag}_client.log"
    [[ -f "$server_log" ]] && cp "$server_log" "$LOG_DIR/${tag}_server.log"
    [[ -f "$iperf_log" ]] && cp "$iperf_log" "$LOG_DIR/${tag}_iperf.log"

    local total_ops="NA"
    local wall_time="NA"
    local throughput="NA"
    local latency_ms="NA"

    if [[ "$status" == "OK" && -f "$client_log" ]]; then
        total_ops="$(extract_metric 'Measured ops\[#\]: [0-9]+' 3 "$client_log")"
        wall_time="$(extract_metric 'Wall-clock time\[s\]: [0-9.]+' 3 "$client_log")"
        throughput="$(extract_metric 'Throughput\[ops/s\]: [0-9.]+' 2 "$client_log")"
        total_ops="${total_ops:-NA}"
        wall_time="${wall_time:-NA}"
        throughput="${throughput:-NA}"

        if [[ "$wall_time" != "NA" ]]; then
            latency_ms="$(awk -v t="$wall_time" -v r="$N_REQUESTS" 'BEGIN { printf "%.6f", (t / r) * 1000.0 }')"
        fi
    fi

    echo "$scheme_label,$N_CLIENTS,$BID_EXPONENT,$block_size,$N_REQUESTS,$BANDWIDTH,$total_ops,$wall_time,$throughput,$latency_ms,$status,$tag" >> "$RESULT_CSV"
    echo "$tag,$scheme_label,$scheme_dir,$script,$N_CLIENTS,$BID_EXPONENT,$block_size,$N_REQUESTS,$BANDWIDTH,$ZIPF_PARAMETER,$RUN_TIMEOUT_SECONDS,$BUILD_PROJECTS,$BUILD_IMAGES,$OUR_ROOT_BUCKET_SIZE,$OUR_COMPETITION_BUCKET_SIZE,$OUR_BUCKET_SIZE,$MVP_BUCKET_SIZE,$MVPSTRONG_ROOT_BUCKET_SIZE,$MVPSTRONG_BUCKET_SIZE,$CONCUR_STASH_SIZE,$CONCUR_Z,$CONCUR_S,$CONCUR_A,$ROOT_DIR" >> "$SETTING_CSV"

    echo "[RESULT] scheme=$scheme_label blockSize=$block_size status=$status time=$wall_time throughput=$throughput latencyMs=$latency_ms"
}

main() {
    write_headers
    build_projects_if_needed

    local -a image_built
    for ((i = 0; i < ${#SCHEME_NAMES[@]}; i++)); do
        image_built[$i]=0
    done

    for block_size in $BLOCK_SIZE_VALUES; do
        for ((i = 0; i < ${#SCHEME_NAMES[@]}; i++)); do
            local build_image="0"
            if [[ "$BUILD_IMAGES" == "1" && "${image_built[$i]}" == "0" ]]; then
                build_image="1"
                image_built[$i]=1
            fi
            run_one "$i" "$block_size" "$build_image"
        done
    done

    echo
    echo "[INFO] All block-size runs finished."
    echo "[INFO] Results: $RESULT_CSV"
    echo "[INFO] Settings: $SETTING_CSV"
    echo "[INFO] Logs: $LOG_DIR"
}

main "$@"
