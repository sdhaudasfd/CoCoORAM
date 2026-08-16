#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../build/install/Opca"
./smartrun.sh opca.benchmark.OpcaBenchmarkClient 100 50 1000 20 4 1024 0.0 127.0.0.1 12341 true 0.0
