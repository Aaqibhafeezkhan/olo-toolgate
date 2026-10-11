// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
}

group = "io.ololabs.toolgate"
version = file("VERSION").readText().trim()

allprojects {
    group = rootProject.group
    version = rootProject.version
    dependencyLocking {
        lockAllConfigurations()
        lockFile.set(file(if (providers.gradleProperty("usePublishedContracts").orNull == "true") "gradle-artifact.lockfile" else "gradle.lockfile"))
    }

    repositories {
        if (providers.gradleProperty("usePublishedContracts").orNull == "true") {
            exclusiveContent {
                forRepository {
                    maven {
                        name = "localContractProof"
                        url = uri(providers.gradleProperty("contractsRepository").getOrElse("${rootDir}/.dev/maven"))
                    }
                }
                filter { includeModule("io.ololabs.toolgate", "toolgate-contracts") }
            }
        }
        mavenCentral()
    }
}

tasks.named("check") { dependsOn("javaCheck") }
tasks.named("build") {
    dependsOn(":contracts-java:build", ":control-plane:build", ":marketplace-api:build", ":marketplace-worker:build", ":sdk-java-api:build", ":sdk-java-processor:build", ":sdk-java-protocol:build", ":sdk-java-runtime-mcp:build", ":sdk-java-testing:build", ":sdk-java-build:build", ":sdk-java-conformance-server:build")
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register("javaCheck") {
    group = "verification"
    description = "Runs checks for all Java/Gradle modules."
    dependsOn(
        ":contracts-java:check",
        ":control-plane:check",
        ":marketplace-api:check",
        ":marketplace-worker:check",
        ":sdk-java-api:check",
        ":sdk-java-processor:check",
        ":sdk-java-protocol:check",
        ":sdk-java-runtime-mcp:check",
        ":sdk-java-testing:check",
        ":sdk-java-build:check",
        ":sdk-java-conformance-server:check",
    )
}

tasks.register("publishContractsToMavenLocal") {
    group = "publishing"
    description = "Publishes the shared Java contracts to the local Maven repository."
    dependsOn(":contracts-java:publishToMavenLocal")
}
