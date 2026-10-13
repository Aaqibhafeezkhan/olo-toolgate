// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "The .tgpkg package validator shared by Control and the Marketplace helper. JDK only, no dependencies."
base { archivesName.set("toolgate-package-archive") }

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The Marketplace helper compiles these sources with `javac --release 17`, so the library stays on Java 17.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    systemProperty("toolgate.root", rootProject.projectDir.absolutePath)
}

tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
