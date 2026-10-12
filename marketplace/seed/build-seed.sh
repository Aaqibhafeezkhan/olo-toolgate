#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Seed build (implementation prompt §3.2 step 1): builds every Java example package from the unchanged examples in
# TOOLGATE_DIR, the same way examples/run-java-examples.sh does, checks each with `toolgate verify`, and writes
# <out>/packages/*.tgpkg plus <out>/seed-manifest.json with one entry per discovered example folder.
#
#   seed/build-seed.sh <out-dir>
#
# TOOLGATE_CLI may name a prebuilt toolgate binary; otherwise the CLI is built from TOOLGATE_DIR. A folder whose build
# fails is still listed (package null) so the seed check reports it instead of dropping it.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=../scripts/toolgate-dir.sh
source "$here/../scripts/toolgate-dir.sh"
out="${1:?usage: build-seed.sh <out-dir>}"
mkdir -p "$out"
out="$(cd "$out" && pwd -P)"

if [ -z "${TOOLGATE_CLI:-}" ]; then
  (cd "$TOOLGATE_DIR" && cargo build -q --release -p toolgate-cli --bin toolgate)
  TOOLGATE_CLI="${CARGO_TARGET_DIR:-$TOOLGATE_DIR/target}/release/toolgate"
fi
export TOOLGATE_CLI

(cd "$TOOLGATE_DIR" && ./gradlew -q --console=plain --configure-on-demand publishJavaSdkLocally)
for build in java-tools mocked-tools; do
  (cd "$TOOLGATE_DIR/examples/$build" && ./gradlew -q --console=plain --continue \
    "-PtoolgateSdkRepo=$TOOLGATE_DIR/build/sdk-repo" toolgatePackage) \
    || echo "seed: some $build packages did not build; the seed check reports them" >&2
done
for package in "$TOOLGATE_DIR"/examples/{java-tools,mocked-tools}/*/build/toolgate/*.tgpkg; do
  [ -e "$package" ] || continue
  "$TOOLGATE_CLI" verify "$package" >/dev/null
done

# The manifest writer is part of the helper; compile it with plain javac (no build tool, no downloads).
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
find "$MARKETPLACE_DIR/helper/src/main/java" -name '*.java' >"$classes/sources"
javac --release 17 -encoding UTF-8 -d "$classes" "@$classes/sources"
rm -rf "$out/packages" "$out/seed-manifest.json"
TOOLGATE_REVISION="$TOOLGATE_REVISION" java -cp "$classes" io.ololabs.marketplace.helper.Main seed-manifest "$TOOLGATE_DIR" "$out"
