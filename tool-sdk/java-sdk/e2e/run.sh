#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The Java path end to end, as a tool author takes it: `toolgate init` writes the Java starter for Gradle and for
# Maven against the SDK published to build/sdk-repo, then `toolgate check` compiles it, runs its unit tests and every
# example, `toolgate pack` builds and validates the .tgpkg, `toolgate dev` answers tools/list over stdio and
# `toolgate dev --digests` reports the descriptor digests. Both builds, and the development server, must give the same
# descriptorSetDigest. Needs a JDK, Maven and cargo.
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
done

sets="$(grep -h descriptorSetDigest "$out"/*-pack.txt "$out"/*-digests.txt | sort -u)"
if [ "$(echo "$sets" | wc -l)" != 1 ]; then
  echo "e2e: the builds and the development server disagree:"
  echo "$sets"
  exit 1
fi
echo "e2e: passed ($sets)"
