// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.gradle;

import org.gradle.api.provider.Property;

/**
 * The {@code toolgate { }} block. {@code descriptorGeneration} is required and has no default (java.md): without it
 * the processor fails the compilation and says so.
 */
public abstract class ToolGateExtension {
    /** The descriptor generation the processor writes; 1 is the only one today. */
    public abstract Property<Integer> getDescriptorGeneration();

    /** The version of the SDK libraries the plugin adds; the plugin's own version by default. */
    public abstract Property<String> getSdkVersion();
}
