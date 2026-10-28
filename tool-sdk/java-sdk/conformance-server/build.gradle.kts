// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    application
}

description = "The fixture server the official MCP conformance suite runs against the Java protocol layer (not published)"

dependencies {
    implementation(project(":sdk-java-protocol"))
}

application {
    mainClass.set("io.ololabs.toolgate.conformance.FixtureServer")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
