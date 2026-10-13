#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The Marketplace's own checks (implementation prompt §1.3). Not a merge gate for PR 1 and PR 2.
#
#   1. isolation check (with its own test cases);
#   2. seed build: every example package, from the unchanged examples;
#   3. helper tests, including /inspect and /sign over every seed package against seed/expected-packages.json;
#   4. the split simulation (the same checks with ToolGate as a git submodule), unless MARKETPLACE_SKIP_SPLIT=1;
#   5. the helper image build, when MARKETPLACE_DOCKER=1;
#   6. the end-to-end import into Quickstart (ci/e2e.sh), when MARKETPLACE_E2E=1. It needs the Marketplace images
#      (scripts/build-images.sh --core-only) and the Quickstart image ($TOOLGATE_DIR/tools/quickstart/check.py --build
#      --build-only) to be built first.
#
# MARKETPLACE_SEED_STRICT=true fails on any knownMissing seed package (release gate).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=../scripts/toolgate-dir.sh
source "$here/../scripts/toolgate-dir.sh"
echo "marketplace: $MARKETPLACE_DIR"
echo "toolgate:    $TOOLGATE_DIR ($TOOLGATE_REVISION)"

bash "$MARKETPLACE_DIR/scripts/isolation-check.sh"

if [ -z "${TOOLGATE_CLI:-}" ]; then
  (cd "$TOOLGATE_DIR" && cargo build -q --release -p toolgate-cli --bin toolgate)
  TOOLGATE_CLI="${CARGO_TARGET_DIR:-$TOOLGATE_DIR/target}/release/toolgate"
fi
export TOOLGATE_CLI

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
bash "$MARKETPLACE_DIR/seed/build-seed.sh" "$work/seed"
export MARKETPLACE_SEED_DIR="$work/seed"

(cd "$MARKETPLACE_DIR/helper" && ./gradlew --no-daemon -q --console=plain check)

if [ "${MARKETPLACE_SKIP_SPLIT:-0}" != 1 ]; then
  bash "$MARKETPLACE_DIR/scripts/split-simulation.sh"
fi

if [ "${MARKETPLACE_DOCKER:-0}" = 1 ]; then
  docker build -f "$MARKETPLACE_DIR/helper/Dockerfile" --build-context marketplace="$MARKETPLACE_DIR" \
    --build-arg TOOLGATE_REVISION="$TOOLGATE_REVISION" \
    --label "io.ololabs.toolgate.revision=$TOOLGATE_REVISION" \
    -t olo-toolgate-marketplace-helper:ci "$TOOLGATE_DIR"
fi
if [ "${MARKETPLACE_E2E:-0}" = 1 ]; then
  bash "$MARKETPLACE_DIR/ci/e2e.sh"
fi
echo "marketplace: checks passed"
