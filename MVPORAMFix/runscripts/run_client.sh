#!/bin/bash

cd ../build/install/SingleServerORAM

./smartrun.sh oram.benchmark.ORAMBenchmarkClient 100 50 1000 12 3 4096 0.01 127.0.0.1 12340 true