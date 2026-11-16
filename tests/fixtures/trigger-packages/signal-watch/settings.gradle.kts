// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
pluginManagement {
    plugins { id("io.ololabs.toolgate.sdk") version providers.gradleProperty("toolgateSdkVersion").get() }
    repositories { maven { url = uri(providers.gradleProperty("toolgateSdkRepo").get()) }; gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { maven { url = uri(providers.gradleProperty("toolgateSdkRepo").get()) }; mavenCentral() }
}
rootProject.name = "signal-watch"
