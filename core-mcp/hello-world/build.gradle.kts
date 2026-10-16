// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins { id("io.ololabs.toolgate.sdk") version "0.10.0-dev" }
group = "io.ololabs.toolgate.examples"
version = "1.0.0"
repositories {
    maven { url = uri(providers.gradleProperty("toolgateSdkRepository").get()) }
    mavenCentral()
}
toolgate { descriptorGeneration.set(1) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
tasks.withType<Jar>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
