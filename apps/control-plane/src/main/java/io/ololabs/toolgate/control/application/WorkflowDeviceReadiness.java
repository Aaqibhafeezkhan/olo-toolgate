// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.ProfilePackage;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.HashSet;

/** Read-only readiness against current desired state and authenticated device observations. */
public final class WorkflowDeviceReadiness {
    private final Codec codec;
    private final Clock clock;
    public WorkflowDeviceReadiness(Codec codec, Clock clock) { this.codec = codec; this.clock = clock; }

    /** Null means ready. A requested rollout is never evidence of applied installation. */
    public String blocker(Store.Session tx, String profileId, ProfilePackage requested, JsonNode status) {
        if (requested.deviceGroupIds() == null || requested.deviceGroupIds().isEmpty()) return null;
        var devices = status.path("deviceIds");
        if (!devices.isArray() || devices.isEmpty()) return "Waiting for an active device in " + String.join(", ", requested.deviceGroupIds());
        var placement = tx.workflowProfiles().devicePlacement(profileId);
        if (placement == null || devices.size() != 1 || !placement.deviceId().equals(devices.get(0).asText()))
            return "Waiting for the exact durable profile device placement";
        long now = clock.millis();
        var seen = new HashSet<String>();
        for (var value : devices) {
            String id = value.asText();
            if (!value.isTextual() || !seen.add(id)) return "Invalid prepared device inventory";
            var row = tx.endpoint(id);
            var entry = tx.load().entries().get(Ids.Kind.DEVICE.id(id));
            if (row == null || entry == null || !entry.enabled()) return "Device " + id + " is not enabled";
            boolean member = requested.deviceGroupIds().stream().anyMatch(group -> {
                var record = tx.load().entries().get(Ids.Kind.DEVICE_GROUP.id(group));
                return record != null && record.enabled() && GroupGraph.members(record, codec).contains(id);
            });
            if (!member) return "Device " + id + " is no longer in the requested device groups";
            var device = codec.model(row.document(), EndpointDeviceRecord.class);
            if (device.state() != EndpointState.ACTIVE || Boolean.FALSE.equals(device.connectionApproved())
                    || device.connectionExpiresAtUnixMs() != null && device.connectionExpiresAtUnixMs() <= now)
                return "Device " + id + " is not approved and active";
            if (device.lastSeenUnixMs() <= 0 || device.lastSeenUnixMs() > now || now - device.lastSeenUnixMs() > 180000)
                return "Waiting for device " + id + " to check in";
            var desiredRow = tx.fleet().get(FleetStore.Kind.DESIRED, id);
            if (desiredRow == null) return "Waiting for package assignment on device " + id;
            var desired = codec.model(desiredRow.document(), FleetDesiredSnapshot.class);
            if (desired.assignments().stream().noneMatch(a -> a.desiredPresence()
                    && a.release().packageId().equals(requested.packageId()) && a.release().version().equals(requested.version())))
                return "Package assignment changed on device " + id;
            var report = device.report();
            if (report == null || !report.deviceId().equals(id) || !report.appliedRevision().equals(desired.generation()))
                return "Waiting for device " + id + " to apply generation " + desired.generation();
            var installed = report.packages().stream().filter(p -> p.packageId().equals(requested.packageId())
                && p.version().equals(requested.version())).toList();
            if (installed.size() != 1 || installed.getFirst().state() != PackageState.READY)
                return "Waiting for package " + requested.packageId() + " " + requested.version() + " to be READY on device " + id;
        }
        return null;
    }
}
