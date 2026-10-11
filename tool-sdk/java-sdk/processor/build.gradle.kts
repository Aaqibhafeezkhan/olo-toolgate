// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate Tool SDK for Java: annotation processor that emits semantic tool descriptors"
base { archivesName.set("toolgate-sdk-processor") }

dependencies {
    api(project(":sdk-java-api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    systemProperty("toolgate.vectors", rootProject.file("tool-sdk/spec/descriptor/vectors").absolutePath)
    systemProperty("toolgate.conformance", rootProject.file("tool-sdk/conformance").absolutePath)
    inputs.dir(rootProject.file("tool-sdk/spec/descriptor/vectors"))
    inputs.dir(rootProject.file("tool-sdk/conformance"))
}
