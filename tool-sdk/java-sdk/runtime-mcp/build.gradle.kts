// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate Tool SDK for Java: the standalone runtime, a plain MCP server over stdio"
base { archivesName.set("toolgate-sdk-runtime-mcp") }

dependencies {
    api(project(":sdk-java-api"))
    api(project(":sdk-java-protocol"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(project(":sdk-java-processor"))
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    systemProperty("toolgate.conformance", rootProject.file("tool-sdk/conformance").absolutePath)
    inputs.dir(rootProject.file("tool-sdk/conformance"))
}
