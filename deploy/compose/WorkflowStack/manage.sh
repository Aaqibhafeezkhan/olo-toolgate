#!/bin/sh
# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
exec python3 "$(dirname "$0")/manage.py" "$@"
