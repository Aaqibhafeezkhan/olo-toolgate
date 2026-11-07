// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
    // TriggerPackages builds real packages with trigger exports for this library's and Control's tests.
    `java-test-fixtures`
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
    // Parity with the CLI: TOOLGATE_TRIGGER_PACKAGE names a package `toolgate pack` built, such as the inbox-watch sample.
    providers.environmentVariable("TOOLGATE_TRIGGER_PACKAGE").orNull?.let { systemProperty("toolgate.triggerPackage", it) }
}

tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
