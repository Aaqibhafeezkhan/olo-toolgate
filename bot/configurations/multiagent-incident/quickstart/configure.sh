#!/bin/sh
# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
set -eu
exec python3 "$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)/quickstart.py" --bot multiagent-incident "$@"
