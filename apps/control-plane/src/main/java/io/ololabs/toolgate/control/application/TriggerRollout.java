// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;
import java.util.Map;

final class TriggerRollout {
    enum DeviceState { PreviousReady, PreviousUnavailable, UpdatedReady, UpdatedUnavailable, UpdatedFailed }

    record Batch(List<String> devices, boolean stalled) {}

    private TriggerRollout() {}

    /** Selects a stable batch without spending slots on devices already unavailable. */
    static Batch select(String maxUnavailable, boolean paused, Map<String, DeviceState> devices) {
        if (devices.containsValue(DeviceState.UpdatedFailed)) return new Batch(List.of(), true);
        if (paused || devices.isEmpty()) return new Batch(List.of(), false);

        int limit = availabilityLimit(maxUnavailable, devices.size());
        long unavailable = devices.values().stream()
                .filter(state -> state == DeviceState.PreviousUnavailable || state == DeviceState.UpdatedUnavailable)
                .count();
        long slots = Math.max(0, limit - unavailable);
        var selected = devices.entrySet().stream()
                .filter(entry -> entry.getValue() == DeviceState.PreviousReady)
                .map(Map.Entry::getKey)
                .sorted()
                .limit(slots)
                .toList();
        return new Batch(selected, false);
    }

    private static int availabilityLimit(String value, int total) {
        if (!value.endsWith("%")) return Integer.parseInt(value);
        int percentage = Integer.parseInt(value.substring(0, value.length() - 1));
        return Math.max(1, total * percentage / 100);
    }
}
