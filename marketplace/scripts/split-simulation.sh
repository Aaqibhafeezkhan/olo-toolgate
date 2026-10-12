#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Trial split (implementation prompt §1.3): copies this folder alone into an empty directory, adds the ToolGate commit
# under test there as a real git submodule at toolgate/, writes "toolgate" into TOOLGATE_SRC, and runs ci/run.sh in
# that layout. Proves the Marketplace moves out with a git mv and a one-line TOOLGATE_SRC change.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=toolgate-dir.sh
source "$here/toolgate-dir.sh"
if [ "$TOOLGATE_REVISION" = unknown ]; then
  echo "split-simulation: $TOOLGATE_DIR is not a git checkout" >&2
  exit 1
fi

split="$(mktemp -d)"
trap 'rm -rf "$split"' EXIT
repo="$split/olo-orgs-marketplace"
mkdir -p "$repo"
# The Marketplace folder as it is now, without local build output or secrets.
tar -C "$MARKETPLACE_DIR" \
  --exclude=./helper/build --exclude=./helper/.gradle --exclude=./deploy/secrets --exclude=./deploy/.env \
  --exclude=./drupal/vendor --exclude=./toolgate -cf - . | tar -C "$repo" -xf -
git -C "$repo" init -q
git -C "$repo" -c protocol.file.allow=always submodule --quiet add "$TOOLGATE_DIR" toolgate
git -C "$repo/toolgate" checkout -q "$TOOLGATE_REVISION"
echo toolgate >"$repo/TOOLGATE_SRC"

resolved="$(bash "$repo/scripts/toolgate-dir.sh")"
[ "$resolved" = "$(cd "$repo/toolgate" && pwd -P)" ] || { echo "split-simulation: resolved $resolved" >&2; exit 1; }
echo "split-simulation: ToolGate $TOOLGATE_REVISION at $resolved"
MARKETPLACE_SKIP_SPLIT=1 bash "$repo/ci/run.sh"
echo "split-simulation: passed"
