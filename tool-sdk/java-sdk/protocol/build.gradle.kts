// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate protocol layer for Java: the MCP 2026-07-28 server core (adapter A1) and Tasks (adapter A2) over stdio and Streamable HTTP"
base { archivesName.set("toolgate-protocol") }

dependencies {
    api(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
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
