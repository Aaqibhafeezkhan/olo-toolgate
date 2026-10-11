// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    id("io.ololabs.toolgate.sdk")
}

version = "1.0.0"

repositories {
    maven { url = uri(providers.gradleProperty("toolgateSdkRepo").get()) }
    mavenCentral()
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

toolgate {
    descriptorGeneration.set(1)
}
