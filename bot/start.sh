#!/bin/sh
# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
set -eu
exec sh "$(CDPATH= cd -- "$(dirname -- "$0")/../deploy/docker/quickstart/dev" && pwd)/start.sh" "$@"
