// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    // The SDK version every example builds against; each example applies the plugin without a version.
    id("io.ololabs.toolgate.sdk") apply false
}

subprojects {
    group = "io.ololabs.examples.mocked"
    version = "0.1.0"
}
