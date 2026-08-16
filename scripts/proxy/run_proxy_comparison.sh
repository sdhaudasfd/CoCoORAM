#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/results/proxy}"
CLIENT_VALUES="${CLIENT_VALUES:-1 5 10 15 20 30 40 50}"
REPEATS="${REPEATS:-1}"
N_REQUESTS="${N_REQUESTS:-1000}"
BID_EXPONENT="${BID_EXPONENT:-18}"
BLOCK_SIZE="${BLOCK_SIZE:-4096}"
BANDWIDTH="${BANDWIDTH:-10gbit}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms16g -Xmx256g}"
BUILD_PROJECTS="${BUILD_PROJECTS:-1}"
BUILD_IMAGES="${BUILD_IMAGES:-1}"

mkdir -p "$OUT_DIR/logs"
RESULT_CSV="$OUT_DIR/proxy_comparison.csv"
echo "scheme,clients,logN,blockSize,requestsPerClient,bandwidth,repeat,totalOps,totalTimeSec,throughputOpsPerSec,latencyMs,status,tag" > "$RESULT_CSV"

declare -a NAMES=("CoCo-ORAM" "Opca" "TaoStore")
declare -a DIRS=("OurORAM" "Opca" "Taostore")
declare -a RUNNERS=("run_our_docker_bandwidth_benchmark.sh" "run_opca_docker_bandwidth_benchmark.sh" "run_taostore_docker_bandwidth_benchmark.sh")

if [[ "$BUILD_PROJECTS" == 1 ]]; then
    for dir in "${DIRS[@]}"; do (cd "$ROOT_DIR/$dir" && chmod +x gradlew && ./gradlew clean installDist); done
fi

extract() { grep "$1" "$2" | tail -1 | awk -F: '{gsub(/[[:space:]]/,"",$2); print $2}'; }

for i in "${!NAMES[@]}"; do
    image_ready=0
    for clients in $CLIENT_VALUES; do
        for ((repeat=1; repeat<=REPEATS; repeat++)); do
            name="${NAMES[$i]}"; dir="${DIRS[$i]}"; runner="${RUNNERS[$i]}"
            build_image=0
            if [[ "$BUILD_IMAGES" == 1 && "$image_ready" == 0 ]]; then build_image=1; image_ready=1; fi
            tag="${name}_c${clients}_repeat${repeat}"
            common=(BANDWIDTH="$BANDWIDTH" N_REQUESTS="$N_REQUESTS" N_CLIENTS="$clients" BID_EXPONENT="$BID_EXPONENT" BLOCK_SIZE="$BLOCK_SIZE" BUILD_IMAGE="$build_image" JAVA_HEAP_OPTS="$JAVA_HEAP_OPTS")
            set +e
            if [[ "$name" == "CoCo-ORAM" ]]; then
                (cd "$ROOT_DIR/$dir" && env "${common[@]}" ROOT_BUCKET_SIZE=1 COMPETITION_BUCKET_SIZE=1 BUCKET_SIZE=3 ZIPF_PARAMETER=0.0 bash "./$runner")
            elif [[ "$name" == "Opca" ]]; then
                (cd "$ROOT_DIR/$dir" && env "${common[@]}" BUCKET_SIZE=4 OPCA_K=40 bash "./$runner")
            else
                (cd "$ROOT_DIR/$dir" && env "${common[@]}" BUCKET_SIZE=4 TAOSTORE_WRITEBACK_THRESHOLD=40 bash "./$runner")
            fi
            rc=$?
            set -e
            log="$ROOT_DIR/$dir/benchmark_logs/docker_bandwidth/client.log"
            if [[ "$rc" == 0 && -f "$log" ]]; then
                total_ops="$(extract 'Measured ops\[#\]:' "$log")"
                total_time="$(extract 'Wall-clock time\[s\]:' "$log")"
                throughput="$(extract 'Throughput\[ops/s\]:' "$log")"
                latency="$(awk -v t="$total_time" -v r="$N_REQUESTS" 'BEGIN {printf "%.6f", t*1000/r}')"
                status=OK
                cp "$log" "$OUT_DIR/logs/${tag}.log"
            else
                total_ops=NA; total_time=NA; throughput=NA; latency=NA; status=FAILED
            fi
            echo "$name,$clients,$BID_EXPONENT,$BLOCK_SIZE,$N_REQUESTS,$BANDWIDTH,$repeat,$total_ops,$total_time,$throughput,$latency,$status,$tag" >> "$RESULT_CSV"
        done
    done
done

python3 "$SCRIPT_DIR/plot_proxy.py"
echo "[RESULT] $OUT_DIR"
