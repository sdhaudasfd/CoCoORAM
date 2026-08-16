#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COCO_DIR="$ROOT_DIR/OurORAM"
MVP_DIR="$ROOT_DIR/MVPORAM"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/results/sensitivity/buckets}"

CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
Z_VALUES="${Z_VALUES:-2 3 4}"
ZETA_VALUES="${ZETA_VALUES:-1 2 3 4}"
REPEATS="${REPEATS:-3}"
RUN_PAUSE_SECONDS="${RUN_PAUSE_SECONDS:-5}"
N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms16g -Xmx256g}"
BUILD_IMAGE="${BUILD_IMAGE:-1}"

mkdir -p "$OUT_DIR"

combine_results() {
    local coco_csv="$1" mvp_csv="$2" output_csv="$3"
    echo "scheme,clients,logN,blockSize,requestsPerClient,bandwidth,Z,zeta,rootBucketSize,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag" > "$output_csv"
    awk -F, 'BEGIN { OFS="," } NR>1 { print $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$18,$19 }' "$coco_csv" >> "$output_csv"
    awk -F, 'BEGIN { OFS="," } NR>1 { print $1,$2,$3,$4,$5,$6,$7,"NA","NA",$8,$9,$10,$11,$12,$13 }' "$mvp_csv" >> "$output_csv"
}

first_group=1
for z in $Z_VALUES; do
    coco_csv="$OUT_DIR/coco_Z${z}.csv"
    mvp_csv="$OUT_DIR/mvp_Z${z}.csv"
    build_image=0
    if [[ "$first_group" == 1 ]]; then build_image="$BUILD_IMAGE"; fi

    (
        cd "$COCO_DIR"
        CLIENT_VALUES="$CLIENT_VALUES" Z_VALUES="$z" ZETA_VALUES="$ZETA_VALUES" \
        REPEATS="$REPEATS" RUN_PAUSE_SECONDS="$RUN_PAUSE_SECONDS" \
        N_REQUESTS="$N_REQUESTS" BID_EXPONENT="$BID_EXPONENT" BLOCK_SIZE="$BLOCK_SIZE" \
        BANDWIDTH="$BANDWIDTH" BUILD_IMAGE="$build_image" RESULT_CSV="$coco_csv" \
        JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" bash ./run_our_parameter_sweep_docker_benchmark.sh
    )
    (
        cd "$MVP_DIR"
        CLIENT_VALUES="$CLIENT_VALUES" Z_VALUES="$z" REPEATS="$REPEATS" \
        RUN_PAUSE_SECONDS="$RUN_PAUSE_SECONDS" N_REQUESTS="$N_REQUESTS" \
        BID_EXPONENT="$BID_EXPONENT" BLOCK_SIZE="$BLOCK_SIZE" ZIPF_PARAMETER=0.0 \
        BANDWIDTH="$BANDWIDTH" BUILD_IMAGE="$build_image" RESULT_CSV="$mvp_csv" \
        JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS" bash ./run_mvp_z_sweep_docker_benchmark.sh
    )

    combine_results "$coco_csv" "$mvp_csv" "$OUT_DIR/bucket_Z${z}.csv"
    first_group=0
done

python3 "$SCRIPT_DIR/plot_buckets.py"
echo "[RESULT] $OUT_DIR"
