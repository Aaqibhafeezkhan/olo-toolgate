// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import static io.ololabs.toolgate.contracts.v2.TriggerDesiredState.Paused;
import static io.ololabs.toolgate.contracts.v2.TriggerDesiredState.Running;
import static io.ololabs.toolgate.contracts.v2.TriggerDesiredState.Stopped;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriggerLifecycleTest {
    @Test
    void resolvesOverlappingGroupsAndExplicitOverrides() {
        var groups = Map.of("g1", Paused, "g2", Running, "unrelated", Stopped);
        assertEquals(new TriggerLifecycle.Desired(Paused, "Group:g1"),
                TriggerLifecycle.resolve(Running, null, null, List.of("g2", "g1"), groups));
        assertEquals(new TriggerLifecycle.Desired(Stopped, "Device"),
                TriggerLifecycle.resolve(Running, null, Stopped, List.of("g2"), groups));
        assertEquals(new TriggerLifecycle.Desired(Running, "Instance"),
                TriggerLifecycle.resolve(Running, Running, Stopped, List.of("g1"), groups));
    }

    @Test
    void resolvesCurrentMembershipAndClearedOverrides() {
        assertEquals(new TriggerLifecycle.Desired(Stopped, "Group:g2"),
                TriggerLifecycle.resolve(Running, null, null, List.of("g1", "g2"), Map.of("g1", Paused, "g2", Stopped)));
        assertEquals(new TriggerLifecycle.Desired(Running, "Group:g2"),
                TriggerLifecycle.resolve(Paused, null, null, List.of("g1", "g2"), Map.of("g2", Running)));
        assertEquals(new TriggerLifecycle.Desired(Paused, "Assignment"),
                TriggerLifecycle.resolve(Paused, null, null, List.of("g1"), Map.of("g2", Stopped)));
        assertEquals(new TriggerLifecycle.Desired(Paused, "Group:g1"),
                TriggerLifecycle.resolve(Running, null, null, List.of("g2", "g1"), Map.of("g1", Paused, "g2", Paused)));
    }
}
