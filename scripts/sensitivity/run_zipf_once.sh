#!/usr/bin/env bash

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
ZIPF_PARAMETER="${ZIPF_PARAMETER:-1.0}"
N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:-"-Xms16g -Xmx256g"}"
BUILD_PROJECT="${BUILD_PROJECT:-1}"
BUILD_IMAGE="${BUILD_IMAGE:-1}"
STOP_ON_FAILURE="${STOP_ON_FAILURE:-1}"
SCHEMES="${SCHEMES:-coco mvp mvpstrong concur}"

RESULT_DIR="${RESULT_DIR:-$ROOT_DIR/benchmark_results}"
RESULT_CSV="${RESULT_CSV:-$RESULT_DIR/zipf_${ZIPF_PARAMETER}_10gbit_N${BID_EXPONENT}_B${BLOCK_SIZE}.csv}"
mkdir -p "$RESULT_DIR"

printf '%s\n' \
  'scheme,clients,logN,blockSize,requestsPerClient,bandwidth,zipfParameter,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag' \
  > "$RESULT_CSV"

contains_scheme() {
    case " $SCHEMES " in
        *" $1 "*) return 0 ;;
        *) return 1 ;;
    esac
}

scheme_dir() {
    case "$1" in
        coco) echo "$ROOT_DIR/OurORAM" ;;
        mvp) echo "$ROOT_DIR/MVPORAM" ;;
        mvpstrong) echo "$ROOT_DIR/MVPORAM-Strong" ;;
        concur) echo "$ROOT_DIR/ConcurORAM" ;;
    esac
}

scheme_label() {
    case "$1" in
        coco) echo 'CoCo-ORAM' ;;
        mvp) echo 'MVP-ORAM' ;;
        mvpstrong) echo 'MVP-ORAM-S' ;;
        concur) echo 'ConcurORAM' ;;
    esac
}

benchmark_script() {
    case "$1" in
        coco) echo './run_our_docker_bandwidth_benchmark.sh' ;;
        mvp) echo './run_mvp_docker_bandwidth_benchmark.sh' ;;
        mvpstrong) echo './run_mvpstrong_docker_bandwidth_benchmark.sh' ;;
        concur) echo './run_concur_docker_bandwidth_benchmark.sh' ;;
    esac
}

normalize_distribution() {
    local scheme="$1"
    local dir="$2"
    local expected_name actual_dir

    case "$scheme" in
        coco) expected_name='OurORAM' ;;
        mvp) expected_name='SingleServerORAM' ;;
        mvpstrong) expected_name='MVPORAM-Strong' ;;
        concur) expected_name='ConcurORAM' ;;
    esac

    if [[ -f "$dir/build/install/$expected_name/smartrun.sh" ]]; then
        sed -i 's/\r$//' "$dir/build/install/$expected_name/smartrun.sh"
        chmod +x "$dir/build/install/$expected_name/smartrun.sh"
        return 0
    fi

    actual_dir="$(find "$dir/build/install" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    if [[ -z "$actual_dir" ]]; then
        echo "[ERROR] No Gradle distribution found for $(scheme_label "$scheme")"
        return 1
    fi

    if [[ ! -f "$actual_dir/smartrun.sh" && -f "$dir/runscripts/smartrun.sh" ]]; then
        cp "$dir/runscripts/smartrun.sh" "$actual_dir/smartrun.sh"
    fi
    if [[ ! -f "$actual_dir/smartrun.sh" ]]; then
        echo "[ERROR] No smartrun.sh found for $(scheme_label "$scheme") in $actual_dir"
        return 1
    fi

    mkdir -p "$dir/build/install/$expected_name"
    if [[ "$actual_dir" != "$dir/build/install/$expected_name" ]]; then
        cp -a "$actual_dir"/. "$dir/build/install/$expected_name"/
    fi
    sed -i 's/\r$//' "$dir/build/install/$expected_name/smartrun.sh"
    chmod +x "$dir/build/install/$expected_name/smartrun.sh"
    echo "[BUILD] Normalized $actual_dir -> $dir/build/install/$expected_name"
}

run_one() {
    local scheme="$1"
    local clients="$2"
    local rebuild_image="$3"
    local dir label script tag output rc wall throughput latency total_ops

    dir="$(scheme_dir "$scheme")"
    label="$(scheme_label "$scheme")"
    script="$(benchmark_script "$scheme")"
    tag="${scheme}_zipf${ZIPF_PARAMETER}_c${clients}_N${BID_EXPONENT}_B${BLOCK_SIZE}_${BANDWIDTH}"

    echo
    echo "============================================================"
    echo "[ZIPF-SWEEP] $tag"
    echo "============================================================"

    set +e
    case "$scheme" in
        coco)
            output="$(cd "$dir" && \
                BUILD_IMAGE="$rebuild_image" BANDWIDTH="$BANDWIDTH" \
                N_CLIENTS="$clients" N_REQUESTS="$N_REQUESTS" BID_EXPONENT="$BID_EXPONENT" \
                ROOT_BUCKET_SIZE=1 COMPETITION_BUCKET_SIZE=1 BUCKET_SIZE=3 \
                BLOCK_SIZE="$BLOCK_SIZE" ZIPF_PARAMETER="$ZIPF_PARAMETER" \
                JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" bash "$script" 2>&1)"
            rc=$?
            ;;
        mvp)
            output="$(cd "$dir" && \
                BUILD_IMAGE="$rebuild_image" BANDWIDTH="$BANDWIDTH" \
                N_CLIENTS="$clients" N_REQUESTS="$N_REQUESTS" BID_EXPONENT="$BID_EXPONENT" \
                BUCKET_SIZE=3 BLOCK_SIZE="$BLOCK_SIZE" ZIPF_PARAMETER="$ZIPF_PARAMETER" \
                JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" bash "$script" 2>&1)"
            rc=$?
            ;;
        mvpstrong)
            output="$(cd "$dir" && \
                BUILD_IMAGE="$rebuild_image" BANDWIDTH="$BANDWIDTH" \
                N_CLIENTS="$clients" N_REQUESTS="$N_REQUESTS" BID_EXPONENT="$BID_EXPONENT" \
                ROOT_BUCKET_SIZE=20 BUCKET_SIZE=3 BLOCK_SIZE="$BLOCK_SIZE" \
                ZIPF_PARAMETER="$ZIPF_PARAMETER" JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
                bash "$script" 2>&1)"
            rc=$?
            ;;
        concur)
            output="$(cd "$dir" && \
                BUILD_IMAGE="$rebuild_image" BANDWIDTH="$BANDWIDTH" \
                N_CLIENTS="$clients" N_REQUESTS="$N_REQUESTS" BID_EXPONENT="$BID_EXPONENT" \
                STASH_SIZE=20 Z=4 S=3 A=3 BLOCK_SIZE="$BLOCK_SIZE" \
                ZIPF_PARAMETER="$ZIPF_PARAMETER" JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" \
                bash "$script" 2>&1)"
            rc=$?
            ;;
    esac
    set -e

    printf '%s\n' "$output"
    total_ops=$((clients * N_REQUESTS))
    wall="$(printf '%s\n' "$output" | sed -n 's/.*Wall-clock time\[s\]:[[:space:]]*\([0-9.]*\).*/\1/p' | tail -n 1)"
    throughput="$(printf '%s\n' "$output" | sed -n 's/.*Throughput\[ops\/s\]:[[:space:]]*\([0-9.]*\).*/\1/p' | tail -n 1)"

    if [[ "$rc" -eq 0 && -n "$wall" && -n "$throughput" ]]; then
        latency="$(awk -v t="$wall" -v r="$N_REQUESTS" 'BEGIN { printf "%.6f", t * 1000.0 / r }')"
        printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,OK,%s\n' \
            "$label" "$clients" "$BID_EXPONENT" "$BLOCK_SIZE" "$N_REQUESTS" \
            "$BANDWIDTH" "$ZIPF_PARAMETER" "$total_ops" "$wall" "$throughput" "$latency" "$tag" \
            >> "$RESULT_CSV"
        return 0
    fi

    printf '%s,%s,%s,%s,%s,%s,%s,NA,NA,NA,NA,FAILED,%s\n' \
        "$label" "$clients" "$BID_EXPONENT" "$BLOCK_SIZE" "$N_REQUESTS" \
        "$BANDWIDTH" "$ZIPF_PARAMETER" "$tag" >> "$RESULT_CSV"
    echo "[ERROR] Failed: $tag (rc=$rc)"
    return 1
}

for scheme in coco mvp mvpstrong concur; do
    contains_scheme "$scheme" || continue
    dir="$(scheme_dir "$scheme")"

    if [[ "$BUILD_PROJECT" == "1" ]]; then
        echo "[BUILD] $(scheme_label "$scheme")"
        (cd "$dir" && chmod +x ./gradlew && ./gradlew clean installDist) || exit 1
    fi
    normalize_distribution "$scheme" "$dir" || exit 1

    first=1
    for clients in $CLIENT_VALUES; do
        rebuild=0
        if [[ "$BUILD_IMAGE" == "1" && "$first" == "1" ]]; then
            rebuild=1
        fi
        if ! run_one "$scheme" "$clients" "$rebuild"; then
            if [[ "$STOP_ON_FAILURE" == "1" ]]; then
                exit 1
            fi
        fi
        first=0
    done
done

echo
echo "[RESULT] $RESULT_CSV"
