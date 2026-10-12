#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Builds, tests and packs every Java example tool against the SDK in this checkout, then proves each package's fat jar
# lists its tools with `java -jar` and an empty environment, as a device runs it. Needs a JDK and cargo.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/build/java-tool-examples"

(cd "$root" && ./gradlew -q --configure-on-demand publishJavaSdkLocally && cargo build -q -p toolgate-cli)
export TOOLGATE_CLI="${CARGO_TARGET_DIR:-$root/target}/debug/toolgate"
(cd "$here" && ./gradlew -q --console=plain "-PtoolgateSdkRepo=$root/build/sdk-repo" check toolgatePackage >/dev/null)

rm -rf "$out"
mkdir -p "$out"
java="$(command -v java)"
request='{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
count=0
for package in "$here"/*/build/toolgate/*.tgpkg; do
  name="$(basename "$package" .tgpkg)"
  "$TOOLGATE_CLI" verify "$package" >/dev/null
  mkdir -p "$out/$name"
  (cd "$out/$name" && jar xf "$package" artifacts/tool.jar)
  (cd "$out/$name" && echo "$request" | env -i "$java" -jar artifacts/tool.jar >"$out/$name.txt" 2>"$out/$name.log")
  grep -q '"tools":\[{' "$out/$name.txt" || { echo "examples: $name did not list its tools"; cat "$out/$name.txt" "$out/$name.log"; exit 1; }
  count=$((count + 1))
done
[ "$count" = 10 ] || { echo "examples: expected 10 packages, found $count"; exit 1; }
echo "examples: passed ($count packages)"
