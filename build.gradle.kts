// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    id("org.sonarqube") version "7.5.0.8588"
}

sonar {
    properties {
        property("sonar.projectKey", providers.environmentVariable("SONAR_PROJECT_KEY").getOrElse("olo-labs_olo-toolgate"))
        providers.environmentVariable("SONAR_ORGANIZATION").orNull?.takeIf { it.isNotBlank() }?.let {
            property("sonar.organization", it)
        }
        property("sonar.qualitygate.wait", "false")
    }
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
                filter { includeModule("io.github.olo-labs", "toolgate-contracts") }
            }
        }
        mavenCentral()
    }
}

tasks.named("check") { dependsOn("javaCheck") }
tasks.named("build") {
    dependsOn(":sdk-json:build")
    dependsOn(":contracts-java:build", ":package-archive:build", ":control-plane:build", ":sdk-java-api:build", ":sdk-java-processor:build", ":sdk-java-protocol:build", ":sdk-java-runtime-mcp:build", ":sdk-java-testing:build", ":sdk-java-build:build", ":sdk-java-gradle-plugin:build", ":sdk-java-maven-plugin:build", ":sdk-java-conformance-server:build")
}

subprojects {
    plugins.withType<JavaPlugin> {
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = "0.8.16"
        }
        // Tool dependencies are pinned above, independently of application locks.
        configurations.matching { it.name == "jacocoAgent" || it.name == "jacocoAnt" }.configureEach {
            resolutionStrategy.deactivateDependencyLocking()
        }
        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
        tasks.named("test") { finalizedBy("jacocoTestReport") }
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

        tasks.withType<Jar>().configureEach {
            manifest.attributes["Implementation-Version"] = project.version.toString()
        }
    }
}

tasks.register("javaCheck") {
    dependsOn(":sdk-json:check")
    group = "verification"
    description = "Runs checks for all Java/Gradle modules."
    dependsOn(
        ":contracts-java:check",
        ":package-archive:check",
        ":control-plane:check",
        ":sdk-java-api:check",
        ":sdk-java-processor:check",
        ":sdk-java-protocol:check",
        ":sdk-java-runtime-mcp:check",
        ":sdk-java-testing:check",
        ":sdk-java-build:check",
        ":sdk-java-gradle-plugin:check",
        ":sdk-java-maven-plugin:check",
        ":sdk-java-conformance-server:check",
    )
}

tasks.register("publishContractsToMavenLocal") {
    group = "publishing"
    description = "Publishes the shared Java contracts to the local Maven repository."
    dependsOn(":contracts-java:publishToMavenLocal")
}

// The Java Tool SDK publishes to build/sdk-repo, for local builds and the plugin end-to-end run (tool-sdk/java-sdk/e2e).
val javaSdkModules = listOf(
    ":sdk-java-api", ":sdk-java-processor", ":sdk-java-protocol", ":sdk-java-runtime-mcp", ":sdk-java-testing",
    ":sdk-java-build", ":sdk-java-gradle-plugin", ":sdk-java-maven-plugin", ":service-authorization",
)
configure(javaSdkModules.map { project(it) }) {
    apply(plugin = "maven-publish")
    // After the module's own script, which names its artifact and applies java-gradle-plugin.
    afterEvaluate {
        val artifact = extensions.getByType<BasePluginExtension>().archivesName.get()
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "sdkLocal"
                    url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
                }
            }
            if (!plugins.hasPlugin("java-gradle-plugin")) {
                publications.create<MavenPublication>("sdk") {
                    from(components["java"])
                    artifactId = artifact
                    if (artifact == "toolgate-maven-plugin") pom { packaging = "maven-plugin" }
                }
            }
            publications.withType<MavenPublication>().configureEach {
                if (name == "pluginMaven") artifactId = artifact
            }
        }
    }
}

tasks.register("publishJavaSdkLocally") {
    group = "publishing"
    description = "Publishes the Java Tool SDK, with its Gradle and Maven plugins, to build/sdk-repo."
    dependsOn(javaSdkModules.map { "$it:publishAllPublicationsToSdkLocalRepository" })
}
tasks.register("publishJavaSdkToMavenLocal") {
    group = "publishing"
    description = "Publishes the Java Tool SDK, with its Gradle and Maven plugins, to the local Maven repository, where toolgate init projects find it."
    dependsOn(javaSdkModules.map { "$it:publishToMavenLocal" })
}
