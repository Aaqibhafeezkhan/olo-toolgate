// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//
// Twelve example tool packages built with the ToolGate Java Tool SDK. Each directory is one package: one @ToolPackage,
// one fat jar, one .tgpkg. They share this build only so `./gradlew check` tests them all at once; copy any one of
// them out with this file's repositories and it builds on its own.
//
// The SDK comes from the local Maven repository (`./gradlew publishJavaSdkToMavenLocal` at the repository root) until
// it is on Maven Central. Pass -PtoolgateSdkRepo=<directory or URL> to use another repository, such as build/sdk-repo.
pluginManagement {
    plugins {
        val sdkVersion = providers.gradleProperty("toolgateSdkVersion").orNull
            ?: file("../../VERSION").takeIf { it.isFile }?.readText()?.trim()
            ?: error("Set -PtoolgateSdkVersion to the installed ToolGate SDK version")
        id("io.ololabs.toolgate.sdk") version sdkVersion
    }
    repositories {
        providers.gradleProperty("toolgateSdkRepo").orNull?.let { maven { url = uri(it) } }
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        providers.gradleProperty("toolgateSdkRepo").orNull?.let { maven { url = uri(it) } }
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "toolgate-java-tool-examples"

include(
    "script-runner",
    "file-download",
    "system-health",
    "cpu-processes",
    "log-insights",
    "disk-usage",
    "endpoint-check",
    "csv-query",
    "webhook-notify",
    "file-cleanup",
    "db-query",
    "hotfolder",
)
