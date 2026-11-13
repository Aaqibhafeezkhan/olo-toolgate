// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.TriggerDesiredState;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

/** Resolves lifecycle overrides for a device that is already in the assignment's target set. */
final class TriggerLifecycle {
    record Desired(TriggerDesiredState state, String source) {}

    private TriggerLifecycle() {}

    static Desired resolve(TriggerDesiredState assignment, TriggerDesiredState instance,
                           TriggerDesiredState device, Collection<String> targetedMemberships,
                           Map<String, TriggerDesiredState> groupOverrides) {
        if (instance != null) return new Desired(instance, "Instance");
        if (device != null) return new Desired(device, "Device");
        return targetedMemberships.stream()
                .filter(groupOverrides::containsKey)
                .min(Comparator.comparingInt((String group) -> restrictiveness(groupOverrides.get(group)))
                        .thenComparing(Comparator.naturalOrder()))
                .map(group -> new Desired(groupOverrides.get(group), "Group:" + group))
                .orElseGet(() -> new Desired(assignment, "Assignment"));
    }

    private static int restrictiveness(TriggerDesiredState state) {
        return switch (state) {
            case Stopped -> 0;
            case Paused -> 1;
            case Running -> 2;
        };
    }
}
