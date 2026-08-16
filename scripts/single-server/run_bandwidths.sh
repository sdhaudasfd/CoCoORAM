#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/results/network}"
N_REQUESTS="${N_REQUESTS:-1000}"
BUILD_PROJECTS="${BUILD_PROJECTS:-1}"
BUILD_IMAGES="${BUILD_IMAGES:-1}"

mkdir -p "$OUT_DIR"
first=1
for bandwidth in 100mbit 1gbit 10gbit; do
    build_projects=0
    build_images=0
    if [[ "$first" == 1 ]]; then
        build_projects="$BUILD_PROJECTS"
        build_images="$BUILD_IMAGES"
    fi
    OUT_DIR="$OUT_DIR" BANDWIDTH="$bandwidth" CLIENT_VALUES="10 50" \
    N_REQUESTS="$N_REQUESTS" BID_EXPONENT=18 BLOCK_SIZE_VALUES=4096 \
    ZIPF_PARAMETER=0.0 BUILD_PROJECTS="$build_projects" BUILD_IMAGES="$build_images" \
    bash "$SCRIPT_DIR/run_clients.sh"
    first=0
done

python3 "$SCRIPT_DIR/plot_bandwidths.py"
echo "[RESULT] $OUT_DIR"
