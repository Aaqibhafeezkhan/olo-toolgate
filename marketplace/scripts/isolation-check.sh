#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Isolation check (implementation prompt §1.3): first its own test cases, then the real tree. Fails on any build,
# source or package dependency between ToolGate and this folder other than through TOOLGATE_DIR.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=toolgate-dir.sh
source "$here/toolgate-dir.sh"
python3 "$here/isolation_check.py" --self-test
python3 "$here/isolation_check.py" "$MARKETPLACE_DIR" "$TOOLGATE_DIR"
