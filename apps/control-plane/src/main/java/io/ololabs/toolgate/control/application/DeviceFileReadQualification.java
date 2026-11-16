// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.util.List;
import java.util.Set;

/** Admission-time complete-scope evidence for explicitly qualified, broker-only device reads. */
public final class DeviceFileReadQualification {
    private DeviceFileReadQualification() {}

    static String key(Codec codec, String tool, String managed) {
        return Canonical.digest((JsonNode) codec.value(codec.json(List.of("device-file-read-qualification/v1", tool, managed))));
    }

    static ObjectNode evidence(JsonNode descriptor, JsonNode registration, JsonNode runtime) {
        if (!"CLIENT".equals(descriptor.path("kind").asText())
                || !"READ_ONLY".equals(descriptor.path("effect").path("safety").asText())
                || descriptor.path("effect").path("openWorld").asBoolean(true)
                || descriptor.path("effect").path("destructive").asBoolean(true)
                || !"READ".equals(registration.path("fileAccess").asText())
                || !descriptor.path("permissions").isArray() || descriptor.path("permissions").size() != 1
                || !"DEVICE_FILES_READ".equals(descriptor.path("permissions").get(0).asText())) return null;
        for (String field : List.of("routes", "destinations", "httpAuth", "tcp"))
            if (!descriptor.path(field).isArray() || !descriptor.path(field).isEmpty()) return null;
        for (String field : List.of("globals", "secrets", "claims"))
            if (!descriptor.path("variables").path(field).isArray() || !descriptor.path("variables").path(field).isEmpty()) return null;
        if (!"JAVA_SDK".equals(runtime.path("kind").asText()) || descriptor.path("execution").path("async").asBoolean(true)) return null;
        String image = runtime.path("image").asText();
        int at = image.lastIndexOf('@');
        if (at < 1 || !image.substring(at + 1).matches("sha256:[a-f0-9]{64}")) return null;
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.put("format", "device-file-read-qualification/v1");
        result.put("toolId", descriptor.path("toolId").asText());
        result.put("managedDigest", InstalledProfiles.packageDigest(registration, runtime));
        result.put("imageDigest", image.substring(at + 1));
        result.put("runtimeDigest", Canonical.digest(runtime));
        result.put("descriptorDigest", Canonical.digest(descriptor));
        result.put("registrationDigest", Canonical.digest(registration));
        return result;
    }

    static void admit(Store.Session tx, Codec codec, EnterpriseInvocation invocation, String attempt, Set<String> pins) {
        if (pins.isEmpty()) return;
        var evaluation = invocation.evaluation();
        String device = evaluation.context().deviceId();
        var entry = tx.load().entries().get(Kind.TOOL.id(evaluation.toolId()));
        if (entry == null || !entry.enabled()) return;
        var tool = codec.model(entry.document(), ControlTool.class);
        if (!tool.packageDigest().equals(evaluation.packageDigest())) return;
        String document = tx.packages().document(key(codec, tool.id(), tool.packageDigest()));
        if (document == null) return;
        var proof = (JsonNode) codec.value(document);
        if (!"device-file-read-qualification/v1".equals(proof.path("format").asText())
                || !tool.id().equals(proof.path("toolId").asText())
                || !tool.packageDigest().equals(proof.path("managedDigest").asText())) return;
        var configuration = tx.endpointConfiguration(device);
        if (configuration == null) return;
        boolean installed = codec.model(configuration.localTools(), LocalToolCatalog.class).tools().stream().anyMatch(item ->
            item.enabled() && item.toolId().equals(tool.id()) && item.packageDigest().equals(tool.packageDigest())
                && item.toolDigest().equals(evaluation.toolDigest()));
        if (!installed) return;
        for (String pin : pins.stream().sorted().toList()) {
            var fields = pin.split("@", -1);
            if (!fields[0].equals(device) || !fields[2].equals(proof.path("imageDigest").asText())) continue;
            var group = tx.load().entries().get(Kind.DEVICE_GROUP.id(fields[1]));
            if (group == null || !group.enabled() || !codec.model(group.document(), ControlDeviceGroup.class).deviceIds().contains(device)) continue;
            tx.enterprise().saveRecoveryScope(attempt, new RecoveryAdmissionPolicy.Scope(device, fields[1], fields[2],
                proof.path("runtimeDigest").asText(), proof.path("descriptorDigest").asText(),
                Canonical.digest((JsonNode) codec.value(entry.document())), tool.revision()));
            return;
        }
    }
}
