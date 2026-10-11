// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
    `maven-publish`
}

description = "Shared protocol and data contracts for OLO ToolGate"
base { archivesName.set("toolgate-contracts") }
version = providers.gradleProperty("contractsPublicationVersion")
    .getOrElse(rootProject.file("packages/contracts/VERSION").readText().trim())

dependencies {
    api(libs.jackson.annotations)
    api(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    systemProperty("toolgate.fixtures", rootProject.file("tests/fixtures/contracts/v1/valid.json").absolutePath)
    systemProperty("toolgate.fixturesV2", rootProject.file("tests/fixtures/contracts/v2/valid.json").absolutePath)
    systemProperty("toolgate.contractsVersion", project.version.toString())
}

tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
tasks.withType<Jar>().configureEach {
    from(project.file("LICENSE")) { into("META-INF") }
    from(rootProject.files("NOTICE.md", "RELEASE-NOTES.md")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.processResources {
    from(rootProject.file("packages/contracts/schemas")) { into("io/ololabs/toolgate/contracts/schemas") }
    from(rootProject.file("packages/contracts/VERSION")) {
        into("META-INF")
        rename { "toolgate-contracts.version" }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "toolgate-contracts"

            pom {
                name.set("OLO ToolGate Contracts")
                description.set(project.description)
                url.set("https://github.com/olo-labs/olo-toolgate")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/olo-labs/olo-toolgate.git")
                    developerConnection.set("scm:git:ssh://git@github.com/olo-labs/olo-toolgate.git")
                    url.set("https://github.com/olo-labs/olo-toolgate")
                }
            }
        }
    }

    repositories {
        maven {
            name = "contractProof"
            url = uri(rootProject.layout.projectDirectory.dir(".dev/maven"))
        }
        if (providers.environmentVariable("CI").orNull == "true" && providers.environmentVariable("MAVEN_REPOSITORY_URL").isPresent) {
            maven {
                name = "remote"
                url = uri(providers.environmentVariable("MAVEN_REPOSITORY_URL").get())
                credentials {
                    username = providers.environmentVariable("MAVEN_USERNAME").orNull
                    password = providers.environmentVariable("MAVEN_PASSWORD").orNull
                }
            }
        }
    }
}
