// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins { application }

description = "JSON Tool SDK: deterministic SDK source compiler and package build"
base { archivesName.set("toolgate-json-sdk") }
application { mainClass.set("io.ololabs.toolgate.jsonsdk.Main"); applicationName = "toolgate-json-sdk" }
dependencies {
    implementation(project(":sdk-java-build"))
    implementation(project(":sdk-java-processor"))
    implementation(project(":package-archive"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }

tasks.processResources {
    val versions = mapOf("sdkVersion" to project.version.toString())
    inputs.properties(versions)
    filesMatching("json-sdk.properties") { expand(versions) }
}

// Keep the positive test project aligned with the SDK under test.
tasks.processTestResources {
    val versions = mapOf("sdkVersion" to project.version.toString())
    inputs.properties(versions)
    filesMatching("hello-world/toolgate.json") { expand(versions) }
}
