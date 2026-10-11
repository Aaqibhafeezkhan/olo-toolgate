// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("toolgateSdkRepo").get()) }
        gradlePluginPortal()
    }
    plugins {
        id("io.ololabs.toolgate.sdk") version providers.gradleProperty("toolgateSdkVersion").get()
    }
}
rootProject.name = "notes"
