#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

cd -- "$(dirname -- "$0")/../.."
lab_dir="$PWD/.dev/debug/hotfolder"
if [[ -z ${DOCKER_CONFIG:-} && -z ${DOCKER_CONTEXT:-} && -f $lab_dir/docker-cli/config.json ]]; then
    export DOCKER_CONFIG="$lab_dir/docker-cli"
fi

python_runner=python3
if [[ $1 == start ]]; then
    python_runner="$lab_dir/venv/bin/python"
    if [[ ! -x $python_runner ]]; then
        python3 -m venv "$lab_dir/venv"
    fi
    if ! "$python_runner" -c 'import langgraph, websockets'; then
        "$python_runner" -m pip install -r examples/langchain/requirements.txt
    fi
fi

exec "$python_runner" debug/hotfolder/bringup.py "$@"
