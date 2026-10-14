// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins { `java-library` }

val usePublishedContracts = providers.gradleProperty("usePublishedContracts").map(String::toBoolean).orElse(false)
val contractsVersion = providers.gradleProperty("contractsVersion").getOrElse(rootProject.file("packages/contracts/VERSION").readText().trim())
dependencies {
    if (usePublishedContracts.get()) {
        implementation("io.github.olo-labs:toolgate-contracts:$contractsVersion")
    } else {
        implementation(project(":contracts-java"))
    }
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { systemProperty("toolgate.published", usePublishedContracts.get()) }
