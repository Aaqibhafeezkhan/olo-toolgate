// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
rootProject.name = "olo-toolgate"

include(":contracts-java")
project(":contracts-java").projectDir = file("packages/contracts/java")

include(":control-plane")
project(":control-plane").projectDir = file("apps/control-plane")

include(":marketplace-api")
project(":marketplace-api").projectDir = file("apps/marketplace-api")

include(":marketplace-worker")
project(":marketplace-worker").projectDir = file("apps/marketplace-worker")

include(":sdk-java-api")
project(":sdk-java-api").projectDir = file("tool-sdk/java-sdk/api")

include(":sdk-java-processor")
project(":sdk-java-processor").projectDir = file("tool-sdk/java-sdk/processor")

include(":sdk-java-protocol")
project(":sdk-java-protocol").projectDir = file("tool-sdk/java-sdk/protocol")

include(":sdk-java-runtime-mcp")
project(":sdk-java-runtime-mcp").projectDir = file("tool-sdk/java-sdk/runtime-mcp")

include(":sdk-java-conformance-server")
project(":sdk-java-conformance-server").projectDir = file("tool-sdk/java-sdk/conformance-server")
