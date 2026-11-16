// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
plugins { id("io.ololabs.toolgate.sdk") }
group = "io.ololabs.acceptance"
version = "0.1.0"
toolgate { descriptorGeneration.set(1) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
