#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Builds the sample tool project in sample/ with the Gradle plugin and with the Maven plugin, against the SDK published
# to build/sdk-repo. Each build compiles with the processor, runs the examples in test mode and packs a .tgpkg with the
# toolgate CLI, which then validates it. Both builds must give the same descriptorSetDigest, and the Gradle dev server
# must answer tools/list over stdio. Needs a JDK, Maven and cargo.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
version="$(cat "$root/VERSION")"
repo="$root/build/sdk-repo"
out="$root/build/java-sdk-e2e"
# Maven caches release versions for good, so the SDK goes into a local repository of its own that is cleared each run.
m2="$root/build/java-sdk-e2e-m2"

(cd "$root" && ./gradlew -q --configure-on-demand publishJavaSdkLocally && cargo build -q -p toolgate-cli)
export TOOLGATE_CLI="$root/target/debug/toolgate"
rm -rf "$out" "$m2/io/ololabs"
mkdir -p "$out"

echo "e2e: Gradle"
cp -R "$here/sample" "$out/gradle"
gradle_props=(-PtoolgateSdkRepo="$repo" -PtoolgateSdkVersion="$version")
"$root/gradlew" -q -p "$out/gradle" "${gradle_props[@]}" toolgatePackage
"$TOOLGATE_CLI" validate "$out/gradle/build/toolgate/notes-1.0.0.tgpkg" | tee "$out/gradle.txt"

request='{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
echo "$request" | "$root/gradlew" -q -p "$out/gradle" "${gradle_props[@]}" toolgateDev >"$out/dev.txt" 2>"$out/dev.log"
grep -q '"notes.notes.greet"' "$out/dev.txt" || { echo "e2e: toolgateDev did not list the tools"; cat "$out/dev.txt" "$out/dev.log"; exit 1; }

echo "e2e: Maven"
cp -R "$here/sample" "$out/maven"
sed -i.bak -e "s#@SDK_REPO_URL@#$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).as_uri())' "$repo")#g" \
  -e "s#@SDK_VERSION@#$version#g" "$out/maven/pom.xml"
mvn -q -B -Dmaven.repo.local="$m2" -f "$out/maven/pom.xml" package
"$TOOLGATE_CLI" validate "$out/maven/target/toolgate/notes-1.0.0.tgpkg" | tee "$out/maven.txt"

gradle_set="$(grep descriptorSetDigest "$out/gradle.txt")"
maven_set="$(grep descriptorSetDigest "$out/maven.txt")"
if [ "$gradle_set" != "$maven_set" ]; then
  echo "e2e: Gradle and Maven packages differ: $gradle_set vs $maven_set"
  exit 1
fi
echo "e2e: passed ($gradle_set)"
