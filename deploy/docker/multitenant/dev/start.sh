#!/bin/sh
# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
set -eu
multitenant_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$multitenant_dir/manage.py" start "$@"
