#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-}"
QUERY_BUDGET="${QUERY_BUDGET:-1000000000}"
SEED="${SEED:-20260814}"
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms4g -Xmx128g}"

cd "$ROOT_DIR"

if [[ "$MODE" != "zeta" && "$MODE" != "logn" ]]; then
    echo "Usage: QUERY_BUDGET=<queries> WORKERS=<workers> bash ./run_overflow.sh zeta|logn"
    exit 1
fi

if [[ -z "${WORKERS:-}" ]]; then
    if [[ "$MODE" == "zeta" ]]; then
        WORKERS=4
    else
        WORKERS=1
    fi
fi

mkdir -p "$ROOT_DIR/build" "$ROOT_DIR/results" "$ROOT_DIR/plots"

echo "[BUILD] Compiling overflow simulator"
javac -encoding UTF-8 -d "$ROOT_DIR/build" "$ROOT_DIR/OurOverflowSimulation.java"

echo "[RUN] mode=$MODE queries=$QUERY_BUDGET workers=$WORKERS seed=$SEED"
java $JAVA_HEAP_OPTS -cp "$ROOT_DIR/build" OurOverflowSimulation \
    --mode "$MODE" \
    --queries "$QUERY_BUDGET" \
    --workers "$WORKERS" \
    --seed "$SEED" \
    --output "$ROOT_DIR/results/overflow_by_${MODE}.csv"

echo "[PLOT] Rendering Fig. 5 data for mode=$MODE"
if [[ "$MODE" == "zeta" ]]; then
    python3 "$ROOT_DIR/plot_overflow_by_zeta.py" \
        "$ROOT_DIR/results/overflow_by_zeta.csv" \
        --out-dir "$ROOT_DIR/plots"
else
    python3 "$ROOT_DIR/plot_overflow_by_logn.py" \
        "$ROOT_DIR/results/overflow_by_logn.csv" \
        --out-dir "$ROOT_DIR/plots"
fi

echo "[DONE] Results: $ROOT_DIR/results"
echo "[DONE] Figures: $ROOT_DIR/plots"
