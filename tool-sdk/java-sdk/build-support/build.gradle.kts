// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate Tool SDK for Java: check, stage and pack, shared by the Gradle and Maven plugins"
base { archivesName.set("toolgate-sdk-build") }

dependencies {
    api(project(":sdk-java-testing"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testAnnotationProcessor(project(":sdk-java-processor"))
}

tasks.processResources {
    val versions = mapOf(
        "sdkVersion" to project.version.toString(),
        "contractsVersion" to rootProject.file("packages/contracts/VERSION").readText().trim(),
    )
    inputs.properties(versions)
    filesMatching("**/sdk.properties") { expand(versions) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.named<JavaCompile>("compileTestJava") { options.compilerArgs.addAll(listOf("-Atoolgate.descriptorGeneration=1", "-Xlint:-processing")) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    // The packing test runs when the toolgate CLI is built: TOOLGATE_CLI=target/debug/toolgate.
    providers.environmentVariable("TOOLGATE_CLI").orNull?.let { systemProperty("toolgate.cli", it) }
}
