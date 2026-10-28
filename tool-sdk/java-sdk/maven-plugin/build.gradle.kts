// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

description = "ToolGate Tool SDK for Java: the Maven plugin, with the dev, check and package goals"
base { archivesName.set("toolgate-maven-plugin") }

val maven = "3.9.11"
dependencies {
    implementation(project(":sdk-java-build"))
    // Maven provides these at run time.
    compileOnly("org.apache.maven:maven-plugin-api:$maven")
    compileOnly("org.apache.maven:maven-core:$maven")
    compileOnly("javax.inject:javax.inject:1")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation("org.apache.maven:maven-plugin-api:$maven")
    testImplementation("org.apache.maven:maven-core:$maven")
    constraints {
        // Preserve Maven 3's XML API while fixing CVE-2025-67030 in the 3.x line.
        compileOnly("org.codehaus.plexus:plexus-utils:3.6.1")
        testImplementation("org.codehaus.plexus:plexus-utils:3.6.1")
        // Maven 3.9 brings Guava 33.4.8; 33.7.2 fixes CVE-2026-102554.
        compileOnly(libs.guava)
        testImplementation(libs.guava)
    }
}

tasks.processResources {
    // A token, not expand(): plugin.xml holds Maven's own ${...} expressions.
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("META-INF/maven/plugin.xml") { filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to tokens) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
