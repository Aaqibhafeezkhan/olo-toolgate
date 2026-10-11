#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The governed path end to end: `toolgate init` writes the Java starter, `toolgate pack` builds its .tgpkg, and a real
# Tool Host admits Control-signed permits for both starter tools and runs them from that package, with the route call
# going through the broker to a loopback upstream. Control and the upstream are fakes in drive.py. Needs a JDK,
# python3, openssl and cargo.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="$root/build/tool-host-e2e"

(cd "$root" && ./gradlew -q --configure-on-demand publishJavaSdkLocally && cargo build -q -p toolgate-cli -p olo-toolgate-tool-host)
bin="${CARGO_TARGET_DIR:-$root/target}/debug"
rm -rf "$out"
mkdir -p "$out"
"$bin/toolgate" init orders --dir "$out/orders" --id acme.orders --publisher Acme --sdk-repo "$root/build/sdk-repo" >/dev/null
(cd "$out/orders" && "$bin/toolgate" pack >"$out/pack.log" 2>&1) || { cat "$out/pack.log"; exit 1; }
python3 "$here/drive.py" "$bin/olo-toolgate-tool-host" "$out/orders/build/toolgate/orders-0.1.0.tgpkg" "$out"
