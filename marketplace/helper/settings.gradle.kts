// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//
// The Marketplace helper's own build. It is not part of the ToolGate root build and declares no repositories: the
// helper uses only the JDK, so building it downloads nothing (Rahul, 2026-10-12: no Maven Central for now).
rootProject.name = "olo-toolgate-marketplace-helper"
