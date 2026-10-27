// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
    repositories {
        // Quarkus plugin dependencies are published directly to Maven Central.
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "olo-toolgate"

include(":sdk-json")
project(":sdk-json").projectDir = file("tool-sdk/json-sdk")

include(":contracts-java")
project(":contracts-java").projectDir = file("packages/contracts/java")

include(":package-archive")
project(":package-archive").projectDir = file("backend-libs/package-archive")

include(":control-plane")
project(":control-plane").projectDir = file("apps/control-plane")

include(":sdk-java-api")
project(":sdk-java-api").projectDir = file("tool-sdk/java-sdk/api")

include(":sdk-java-processor")
project(":sdk-java-processor").projectDir = file("tool-sdk/java-sdk/processor")

include(":sdk-java-protocol")
project(":sdk-java-protocol").projectDir = file("tool-sdk/java-sdk/protocol")

include(":sdk-java-runtime-mcp")
project(":sdk-java-runtime-mcp").projectDir = file("tool-sdk/java-sdk/runtime-mcp")

include(":sdk-java-testing")
project(":sdk-java-testing").projectDir = file("tool-sdk/java-sdk/testing")

include(":sdk-java-build")
project(":sdk-java-build").projectDir = file("tool-sdk/java-sdk/build-support")

include(":sdk-java-gradle-plugin")
project(":sdk-java-gradle-plugin").projectDir = file("tool-sdk/java-sdk/gradle-plugin")

include(":sdk-java-maven-plugin")
project(":sdk-java-maven-plugin").projectDir = file("tool-sdk/java-sdk/maven-plugin")

include(":sdk-java-conformance-server")
project(":sdk-java-conformance-server").projectDir = file("tool-sdk/java-sdk/conformance-server")

include(":service-authorization")
project(":service-authorization").projectDir = file("backend-libs/service-authorization")
