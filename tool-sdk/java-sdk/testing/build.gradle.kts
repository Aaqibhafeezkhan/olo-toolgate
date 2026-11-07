// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate Tool SDK for Java: ToolHarness, which runs tools in test mode with recorded exchanges"
base { archivesName.set("toolgate-sdk-testing") }

dependencies {
    api(project(":sdk-java-runtime-mcp"))
    // TriggerHarness speaks the trigger frames through the generated bindings.
    api(project(":contracts-java"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testAnnotationProcessor(project(":sdk-java-processor"))
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.named<JavaCompile>("compileTestJava") { options.compilerArgs.addAll(listOf("-Atoolgate.descriptorGeneration=1", "-Xlint:-processing")) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    systemProperty("toolgate.conformance", rootProject.file("tool-sdk/conformance").absolutePath)
    inputs.dir(rootProject.file("tool-sdk/conformance"))
}
