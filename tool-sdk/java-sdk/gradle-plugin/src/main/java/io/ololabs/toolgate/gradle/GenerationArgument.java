// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.gradle;

import java.util.List;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.process.CommandLineArgumentProvider;

/** {@code -Atoolgate.descriptorGeneration=<n>} on every Java compilation, when the build sets it. */
final class GenerationArgument implements CommandLineArgumentProvider {
    static final String OPTION = "toolgate.descriptorGeneration";

    private final Property<Integer> generation;

    GenerationArgument(Property<Integer> generation) {
        this.generation = generation;
    }

    @Input
    @Optional
    public Property<Integer> getGeneration() {
        return generation;
    }

    @Override
    public Iterable<String> asArguments() {
        return generation.isPresent() ? List.of("-A" + OPTION + "=" + generation.get()) : List.of();
    }
}
