#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../build/install/Opca"
./smartrun.sh opca.server.StorageServer 127.0.0.1 12340 20 4 1024 0
