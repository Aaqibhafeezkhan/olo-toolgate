// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    java
}

// Any JDK 17 or newer builds it; the bytecode targets Java 17.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.jar {
    archiveFileName.set("marketplace-helper.jar")
    manifest {
        attributes("Main-Class" to "io.ololabs.marketplace.helper.Main")
    }
}

// MARKETPLACE_DIR, TOOLGATE_DIR and TOOLGATE_CLI come from scripts/toolgate-dir.sh through ci/run.sh, or from the environment.
val toolgateDir = providers.environmentVariable("TOOLGATE_DIR")
val marketplaceDir = providers.environmentVariable("MARKETPLACE_DIR")
val toolgateCli = providers.environmentVariable("TOOLGATE_CLI")
val seedDir = providers.environmentVariable("MARKETPLACE_SEED_DIR")

// The tests use a small runner of their own, so the build needs no test framework from a repository.
val helperTest by tasks.registering(JavaExec::class) {
    description = "Runs the helper's tests."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.ololabs.marketplace.helper.TestMain")
    environment("TOOLGATE_DIR", toolgateDir.getOrElse(""))
    environment("MARKETPLACE_DIR", marketplaceDir.getOrElse(""))
    environment("TOOLGATE_CLI", toolgateCli.getOrElse(""))
    environment("MARKETPLACE_SEED_DIR", seedDir.getOrElse(""))
}

tasks.named("check") {
    dependsOn(helperTest)
}

// Gradle's own test task expects a framework; helperTest replaces it.
tasks.named<Test>("test") {
    enabled = false
}
