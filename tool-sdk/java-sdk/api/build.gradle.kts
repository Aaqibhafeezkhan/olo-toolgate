// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate Tool SDK for Java: authoring annotations, ToolContext and errors"
base { archivesName.set("toolgate-sdk-api") }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// The community guide ships in the API jar, which every tool depends on.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("TOOL-SDK-GUIDE.md")) { into("META-INF/toolgate") }
}
