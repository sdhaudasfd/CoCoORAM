#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULT_DIR="$ROOT_DIR/results/sensitivity/zipf"
mkdir -p "$RESULT_DIR"

REPEATS="${REPEATS:-5}"
ZIPF_VALUES="${ZIPF_VALUES:-0 1 2}"
CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
SCHEMES="${SCHEMES:-coco mvp mvpstrong concur}"

first=1
for alpha in $ZIPF_VALUES; do
    for ((repeat = 1; repeat <= REPEATS; repeat++)); do
        build=0
        image=0
        if (( first == 1 )); then
            build="${BUILD_PROJECT:-1}"
            image="${BUILD_IMAGE:-1}"
            first=0
        fi
        CLIENT_VALUES="$CLIENT_VALUES" ZIPF_PARAMETER="$alpha" SCHEMES="$SCHEMES" \
        N_REQUESTS="${N_REQUESTS:-1000}" BID_EXPONENT="${BID_EXPONENT:-18}" \
        BLOCK_SIZE="${BLOCK_SIZE:-4096}" BANDWIDTH="${BANDWIDTH:-10gbit}" \
        JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms16g -Xmx256g}" \
        BUILD_PROJECT="$build" BUILD_IMAGE="$image" STOP_ON_FAILURE=1 \
        RESULT_CSV="$RESULT_DIR/zipf_alpha${alpha}_repeat${repeat}.csv" \
        bash "$SCRIPT_DIR/run_zipf_once.sh"
    done
done

python3 "$SCRIPT_DIR/plot_zipf.py"
