#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The only resolver of the ToolGate source tree (implementation prompt §1.4). Reads TOOLGATE_SRC, a path relative to
# the marketplace/ folder ("..", or "toolgate" once the Marketplace is its own repository with olo-toolgate as a
# submodule), and prints its absolute, symlink-free path.
#
# Executed: prints TOOLGATE_DIR. Sourced: exports MARKETPLACE_DIR, TOOLGATE_DIR and TOOLGATE_REVISION.
set -euo pipefail

marketplace_resolve() {
  local here marketplace src dir
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  marketplace="$(cd "$here/.." && pwd -P)"
  [ -f "$marketplace/TOOLGATE_SRC" ] || { echo "toolgate-dir: $marketplace/TOOLGATE_SRC is missing" >&2; return 1; }
  src="$(head -n 1 "$marketplace/TOOLGATE_SRC" | tr -d '[:space:]')"
  case "$src" in
    ''|/*) echo "toolgate-dir: TOOLGATE_SRC must be a relative path, got '$src'" >&2; return 1 ;;
  esac
  dir="$(cd "$marketplace/$src" 2>/dev/null && pwd -P)" || { echo "toolgate-dir: $marketplace/$src does not exist" >&2; return 1; }
  # A ToolGate checkout. backend-libs/package-archive joins this list in PR 3, when that library exists.
  local marker
  for marker in VERSION packages/contracts/VERSION tool-sdk/cli/Cargo.toml examples/java-tools/settings.gradle.kts; do
    [ -e "$dir/$marker" ] || { echo "toolgate-dir: $dir is not a ToolGate checkout ($marker missing)" >&2; return 1; }
  done
  MARKETPLACE_DIR="$marketplace"
  TOOLGATE_DIR="$dir"
  # The commit under test; image builds have no .git and pass it in as TOOLGATE_REVISION instead.
  TOOLGATE_REVISION="$(git -C "$dir" rev-parse HEAD 2>/dev/null || echo "${TOOLGATE_REVISION:-unknown}")"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  marketplace_resolve
  echo "$TOOLGATE_DIR"
else
  marketplace_resolve
  export MARKETPLACE_DIR TOOLGATE_DIR TOOLGATE_REVISION
fi
