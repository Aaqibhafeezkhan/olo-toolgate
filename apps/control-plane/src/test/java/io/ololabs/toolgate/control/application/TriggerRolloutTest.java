// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import static io.ololabs.toolgate.control.application.TriggerRollout.DeviceState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriggerRolloutTest {
    @Test
    void waitsForReplacementReadinessBeforeSpendingAnotherSlot() {
        assertEquals(List.of("d1"), TriggerRollout.select("1", false,
                Map.of("d2", PreviousReady, "d1", PreviousReady)).devices());
        assertTrue(TriggerRollout.select("1", false,
                Map.of("d1", UpdatedUnavailable, "d2", PreviousReady)).devices().isEmpty());
        assertEquals(List.of("d2"), TriggerRollout.select("1", false,
                Map.of("d1", UpdatedReady, "d2", PreviousReady)).devices());
    }

    @Test
    void countsExistingUnavailabilityAndHonorsPauseAndFailure() {
        assertEquals(List.of("d2"), TriggerRollout.select("50%", false,
                Map.of("d1", PreviousUnavailable, "d2", PreviousReady, "d3", PreviousReady, "d4", UpdatedReady)).devices());
        var paused = TriggerRollout.select("1", true, Map.of("d1", PreviousReady));
        assertTrue(paused.devices().isEmpty());
        assertFalse(paused.stalled());
        var failed = TriggerRollout.select("100%", false, Map.of("d1", UpdatedFailed, "d2", PreviousReady));
        assertTrue(failed.devices().isEmpty());
        assertTrue(failed.stalled());
    }
}
