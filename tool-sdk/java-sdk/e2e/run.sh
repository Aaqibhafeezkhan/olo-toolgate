#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The Java path end to end, as a tool author takes it: `toolgate init` writes the Java starter for Gradle and for
# Maven against the SDK published to build/sdk-repo, then `toolgate check` compiles it, runs its unit tests and every
# example, `toolgate pack` builds and validates the .tgpkg, `toolgate dev` answers tools/list over stdio and
# `toolgate dev --digests` reports the descriptor digests, and the package's one fat jar answers tools/list with
# `java -jar` and nothing else: no build tool, no class path, an empty environment. Both builds, and the development
# server, must give the same descriptorSetDigest. Needs a JDK, Maven and cargo.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
repo="$root/build/sdk-repo"
out="$root/build/java-sdk-e2e"

(cd "$root" && ./gradlew -q --configure-on-demand publishJavaSdkLocally && cargo build -q -p toolgate-cli)
toolgate="${CARGO_TARGET_DIR:-$root/target}/debug/toolgate"
rm -rf "$out"
mkdir -p "$out"
# Maven caches release versions for good, so the SDK goes into a local repository of its own that is cleared each run.
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=$root/build/java-sdk-e2e-m2"
rm -rf "$root/build/java-sdk-e2e-m2/io/ololabs"

request='{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
for build in gradle maven; do
  echo "e2e: $build"
  project="$out/$build"
  "$toolgate" init orders --build "$build" --dir "$project" --id acme.orders --publisher Acme --sdk-repo "$repo" >/dev/null
  (cd "$project" && "$toolgate" check >"$out/$build-check.log" 2>&1) || { cat "$out/$build-check.log"; exit 1; }
  (cd "$project" && "$toolgate" pack 2>"$out/$build-pack.log" | tee "$out/$build-pack.txt") || { cat "$out/$build-pack.log"; exit 1; }
  (cd "$project" && echo "$request" | "$toolgate" dev >"$out/$build-dev.txt" 2>"$out/$build-dev.log")
  grep -q '"orders.greet"' "$out/$build-dev.txt" || { echo "e2e: toolgate dev did not list the tools"; cat "$out/$build-dev.txt" "$out/$build-dev.log"; exit 1; }
  (cd "$project" && "$toolgate" dev --digests 2>"$out/$build-digests.log" | tee "$out/$build-digests.txt")
  package="$(find "$project/build/toolgate" "$project/target/toolgate" -maxdepth 1 -name "*.tgpkg" 2>/dev/null | head -n 1 || true)"
  mkdir -p "$out/$build-device"
  (cd "$out/$build-device" && jar xf "$package" artifacts/tool.jar)
  [ "$(cd "$out/$build-device" && find . -type f)" = "./artifacts/tool.jar" ] || { echo "e2e: the package is not one jar"; exit 1; }
  java="$(command -v java)"
  (cd "$out/$build-device" && echo "$request" | env -i "$java" -jar artifacts/tool.jar >"$out/$build-jar.txt" 2>"$out/$build-jar.log")
  grep -q '"orders.greet"' "$out/$build-jar.txt" || { echo "e2e: java -jar tool.jar did not list the tools"; cat "$out/$build-jar.txt" "$out/$build-jar.log"; exit 1; }
done

sets="$(grep -h descriptorSetDigest "$out"/*-pack.txt "$out"/*-digests.txt | sort -u)"
if [ "$(echo "$sets" | wc -l)" != 1 ]; then
  echo "e2e: the builds and the development server disagree:"
  echo "$sets"
  exit 1
fi
echo "e2e: passed ($sets)"
