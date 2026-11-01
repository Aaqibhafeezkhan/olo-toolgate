// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
plugins { `java-library`; `maven-publish` }
base { archivesName.set("toolgate-service-authorization") }
group = "io.github.olo-labs"
description = "Downstream service verification of Control-signed exact-request permits"
dependencies {
    api(project(":contracts-java"))
    implementation(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly("org.postgresql:postgresql:42.7.13")
}

tasks.withType<Test>().configureEach {
    systemProperty("toolgate.httpBindingVectors", rootProject.file("tests/fixtures/security/http-request-binding-v1.json").absolutePath)
}
