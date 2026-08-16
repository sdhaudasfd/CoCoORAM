# CoCo Artifact

## Contents

- [1. Overview](#1-overview)
- [2. Requirements](#2-requirements)
- [3. Experiments](#3-experiments)
  - [3.1 Overflow](#31-overflow)
  - [3.2 Bandwidth and Latency Breakdown](#32-bandwidth-and-latency-breakdown)
  - [3.3 ORAM Comparison](#33-oram-comparison)
  - [3.4 Search Application](#34-search-application)
  - [3.5 BFT Application](#35-bft-application)
  - [3.6 Access Skew](#36-access-skew)
  - [3.7 Bounded Execution](#37-bounded-execution)
  - [3.8 Bucket Capacities](#38-bucket-capacities)
  - [3.9 Proxy Comparison](#39-proxy-comparison)
- [4. Artifact Provenance](#4-artifact-provenance)

## 1. Overview

This repository contains the artifact for **"CoCo: Breaking the Collision-Contention Barrier in Multi-Client ORAM."** The scripts reproduce the CoCo-ORAM evaluation and comparisons with ConcurORAM, MVP-ORAM, MVP-ORAM-S, Opca, TaoStore, and BlockSSE.

## 2. Requirements

- Ubuntu 20.04/22.04 LTS
- OpenJDK 11+
- Python 3.9+
- Docker Engine
- `matplotlib`

```bash
sudo apt update
sudo apt install -y openjdk-11-jdk python3 python3-pip docker.io
python3 -m pip install -r requirements.txt
```

## 3. Experiments

### 3.1 Overflow

```bash
cd Overflow
QUERY_BUDGET=1000000000 WORKERS=4 JAVA_HEAP_OPTS="-Xms4g -Xmx128g" bash ./run_overflow.sh zeta
QUERY_BUDGET=1000000000 WORKERS=1 JAVA_HEAP_OPTS="-Xms4g -Xmx128g" bash ./run_overflow.sh logn
```

Outputs for Fig. 5 are written to `Overflow/results/` and `Overflow/plots/`.

### 3.2 Bandwidth and Latency Breakdown

```bash
cd OurORAM
chmod +x gradlew && ./gradlew clean installDist
BUILD_IMAGE=1 JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash ./run_our_bandwidth_table.sh
BUILD_IMAGE=0 JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash ./run_our_latency_breakdown.sh
```

This produces Table IV and Fig. 6 under `OurORAM/results/`.

### 3.3 ORAM Comparison

```bash
for d in OurORAM MVPORAM MVPORAM-Strong ConcurORAM; do
  (cd "$d" && chmod +x gradlew && ./gradlew clean installDist)
done

BUILD_IMAGES=1 ZIPF_PARAMETER=0.0 bash scripts/single-server/run_clients.sh
BUILD_IMAGES=0 ZIPF_PARAMETER=0.0 bash scripts/single-server/run_block_sizes.sh
BUILD_IMAGES=0 ZIPF_PARAMETER=0.0 bash scripts/single-server/run_oram_sizes.sh
BUILD_PROJECTS=0 BUILD_IMAGES=0 JAVA_HEAP_OPTS="-Xms16g -Xmx256g" \
  bash scripts/single-server/run_bandwidths.sh
python3 scripts/single-server/plot_results.py
```

Results for Figs. 7 and 8 are written to `wan_results/`; Fig. 9 is written to `results/network/`. `BID_EXPONENT=h` denotes $2^h$ logical blocks and leaves in every implementation.

### 3.4 Search Application

The Enron-derived dataset is included in `OurSSE/DataSet/`. Run CoCo-SSE and the serialized BlockSSE baseline with uniform searches:

```bash
JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash scripts/applications/run_sse.sh
```

CSV files and the two Fig. 10 PDFs are written to `results/applications/`.

### 3.5 BFT Application

Fig. 11 requires one machine per replica and a separate client machine. Copy this artifact to every machine, then build both implementations with `./gradlew clean installDist`. Set the comma-separated private IPs in replica-ID order.

On replica machine `i` (set `SCHEME` to `coco` or `mvp`):

```bash
SCHEME=coco
REPLICAS=m SERVER_HOSTS="HOSTS" SERVER_START_ID=i SERVER_COUNT=1 \
CLIENT_VALUES="1 5 10 15 20 30 40 50" N_REQUESTS=256 BID_EXPONENT=18 \
ROOT_BUCKET_SIZE=1 COMPETITION_BUCKET_SIZE=1 BUCKET_SIZE=3 BLOCK_SIZE=4096 \
ZIPF_PARAMETER=0.0 BANDWIDTH=real JAVA_HEAP_OPTS="-Xms4g -Xmx24g" \
bash "scripts/multiserver/run_${SCHEME}_server.sh"
```

After all replicas are ready, run on the client machine:

```bash
SCHEME=coco
REPLICAS=m SERVER_HOSTS="HOSTS" CLIENT_MACHINE_INDEX=0 CLIENT_MACHINE_COUNT=1 \
CLIENT_VALUES="1 5 10 15 20 30 40 50" N_REQUESTS=256 BID_EXPONENT=18 \
ROOT_BUCKET_SIZE=1 COMPETITION_BUCKET_SIZE=1 BUCKET_SIZE=3 BLOCK_SIZE=4096 \
ZIPF_PARAMETER=0.0 BANDWIDTH=real JAVA_HEAP_OPTS="-Xms8g -Xmx64g" \
bash "scripts/multiserver/run_${SCHEME}_client.sh"
```

Use `SCHEME=coco` or `SCHEME=mvp`; for MVP, `ROOT_BUCKET_SIZE` and `COMPETITION_BUCKET_SIZE` are ignored. Repeat with $m\in\{1,4,7,10\}$.

Copy the server-0 result CSVs to `results/multiserver/coco.csv` and `results/multiserver/mvp.csv`, then run `python3 scripts/multiserver/plot_results.py`. The single-server panels are produced by Section 3.3.

### 3.6 Access Skew

Run all four schemes with $\alpha\in\{0,1,2\}$ to reproduce Fig. 12:

```bash
REPEATS=5 JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash scripts/sensitivity/run_zipf.sh
```

CSV files and four throughput PDFs are written to `results/sensitivity/zipf/`.

### 3.7 Bounded Execution

Both schemes use an execution-window limit of 10; for fewer than 10 clients, all available clients execute.

```bash
REPEATS=5 JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash scripts/sensitivity/run_bounded.sh
```

CSV files and the two Fig. 13 PDFs are written to `results/sensitivity/bounded/`.

### 3.8 Bucket Capacities

Run CoCo-ORAM with $Z\in\{2,3,4\}$ and $\zeta\in\{1,2,3,4\}$, and MVP-ORAM with the same values of $Z$:

```bash
REPEATS=3 JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash scripts/sensitivity/run_bucket_capacity.sh
```

CSVs, six Fig. 14 panel PDFs, and a shared legend PDF are written to `results/sensitivity/buckets/`.

### 3.9 Proxy Comparison

Run CoCo-ORAM, Opca, and TaoStore with uniform requests:

```bash
JAVA_HEAP_OPTS="-Xms16g -Xmx256g" bash scripts/proxy/run_proxy_comparison.sh
```

The CSV and two Fig. 15 PDFs are written to `results/proxy/`.

## 4. Artifact Provenance

- The MVP-ORAM implementation is based on the authors' [official Zenodo artifact](https://zenodo.org/records/17842154).
- Baseline integration changes are limited to build/runtime support, exposed experiment parameters, uniform request generation, and metric collection; the protocol logic is unchanged.
- The remaining implementations, Docker environments, and reproduction scripts used by this evaluation are included in this repository under a common benchmarking interface.
