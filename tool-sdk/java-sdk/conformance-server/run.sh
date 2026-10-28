#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Runs the official MCP conformance suite (2026-07-28 requirements and the tasks-* extension scenarios) against
# the Java fixture server. Needs Node.js and npm. Exits non-zero on a failure outside expected-failures.yml or a
# stale entry in it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
suite="@modelcontextprotocol/conformance@0.2.0-alpha.12"
port="${TOOLGATE_CONFORMANCE_PORT:-18765}"
out="$here/build/conformance"

(cd "$root" && ./gradlew -q --configure-on-demand :sdk-java-conformance-server:installDist)
mkdir -p "$out/npm"
npm install --silent --no-audit --no-fund --prefix "$out/npm" "$suite" >/dev/null
"$here/build/install/sdk-java-conformance-server/bin/sdk-java-conformance-server" "$port" >"$out/server.log" 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  grep -q "fixture server at" "$out/server.log" 2>/dev/null && break
  sleep 0.2
done

"$out/npm/node_modules/.bin/conformance" server --url "http://localhost:$port/mcp" --requirements 2026-07-28 \
  --expected-failures "$here/expected-failures.yml" -o "$out/results"
