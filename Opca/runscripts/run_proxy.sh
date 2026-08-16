#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../build/install/Opca"
./smartrun.sh opca.proxy.OpcaProxy 127.0.0.1 12341 127.0.0.1 12340 20 4 1024 40 1
