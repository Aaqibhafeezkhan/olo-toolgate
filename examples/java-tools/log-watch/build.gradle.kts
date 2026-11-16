// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
plugins { id("io.ololabs.toolgate.sdk") }
toolgate { descriptorGeneration.set(1) }
dependencies {
    implementation("com.google.re2j:re2j:1.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.2")
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
