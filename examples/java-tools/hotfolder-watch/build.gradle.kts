// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    id("io.ololabs.toolgate.sdk")
}

toolgate {
    // Pinned so an SDK upgrade never changes the generated descriptors until you move it.
    descriptorGeneration.set(1)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.2")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
