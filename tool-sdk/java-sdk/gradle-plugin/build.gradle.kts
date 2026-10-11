// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-gradle-plugin`
}

description = "ToolGate Tool SDK for Java: the Gradle plugin, with toolgateDev, toolgateCheck and toolgatePackage"
base { archivesName.set("toolgate-gradle-plugin") }

gradlePlugin {
    plugins {
        create("toolgateSdk") {
            id = "io.ololabs.toolgate.sdk"
            implementationClass = "io.ololabs.toolgate.gradle.ToolGatePlugin"
            displayName = "ToolGate Tool SDK"
            description = "Builds, checks, runs and packages ToolGate tools written with the Java SDK"
        }
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.processResources {
    val versions = mapOf("sdkVersion" to project.version.toString())
    inputs.properties(versions)
    filesMatching("**/plugin.properties") { expand(versions) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
