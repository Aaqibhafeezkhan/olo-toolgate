// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//
// Example tool packages that return fixed, fictional data, for demos, agent design and tests without real systems.
// Each directory is one package: one @ToolPackage, one fat jar, one .tgpkg. They share this build only so
// `./gradlew check` tests them all at once.
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

rootProject.name = "toolgate-mocked-tool-examples"

include(
    "bank-loan",
    "healthcare",
    "server-recovery",
    "customer-support",
    "hr-helpdesk",
    "fraud-detection",
    "insurance-claims",
)
