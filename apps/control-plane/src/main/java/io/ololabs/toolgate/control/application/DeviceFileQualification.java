// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.util.List;
import java.util.Set;

/** Candidates are not authority: ADR 075 requires live exact-owner qualification and broker closure. */
public final class DeviceFileQualification {
    private DeviceFileQualification() {}

    static String key(Codec codec, String tool, String managed) {
        return Canonical.digest((JsonNode) codec.value(codec.json(List.of("native-file-qualification/v1", tool, managed))));
    }

    static ObjectNode evidence(JsonNode descriptor, JsonNode registration, JsonNode runtime) {
        boolean write="WRITE".equals(registration.path("fileAccess").asText());
        var permissions=new java.util.TreeSet<String>();
        descriptor.path("permissions").forEach(p->permissions.add(p.asText()));
        if (!"CLIENT".equals(descriptor.path("kind").asText())
                || !(write?Set.of("NON_IDEMPOTENT","IDEMPOTENT").contains(descriptor.path("effect").path("safety").asText()):"READ_ONLY".equals(descriptor.path("effect").path("safety").asText()))
                || descriptor.path("effect").path("openWorld").asBoolean(true)
                || descriptor.path("effect").path("destructive").asBoolean(true)
                || !Set.of("READ","WRITE").contains(registration.path("fileAccess").asText())
                || !descriptor.path("permissions").isArray() || descriptor.path("permissions").size()!=permissions.size()
                || !permissions.equals(write?Set.of("DEVICE_FILES_READ","DEVICE_FILES_WRITE"):Set.of("DEVICE_FILES_READ"))) return null;
        for (String field : List.of("routes", "destinations", "httpAuth", "tcp"))
            if (!descriptor.path(field).isArray() || !descriptor.path(field).isEmpty()) return null;
        for (String field : List.of("globals", "secrets", "claims"))
            if (!descriptor.path("variables").path(field).isArray() || !descriptor.path("variables").path(field).isEmpty()) return null;
        if (!"JAVA_SDK".equals(runtime.path("kind").asText()) || descriptor.path("execution").path("async").asBoolean(true)) return null;
        String image = runtime.path("image").asText();
        int at = image.lastIndexOf('@');
        if (at < 1 || !image.substring(at + 1).matches("sha256:[a-f0-9]{64}")) return null;
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.put("format", "native-file-qualification/v1");
        result.put("writeAccess",write);
        result.put("toolId", descriptor.path("toolId").asText());
        result.put("managedDigest", InstalledProfiles.packageDigest(registration, runtime));
        result.put("imageDigest", image.substring(at + 1));
        result.put("runtimeDigest", Canonical.digest(runtime));
        result.put("descriptorDigest", Canonical.digest(descriptor));
        result.put("registrationDigest", Canonical.digest(registration));
        return result;
    }

    static void admit(Store.Session tx, Codec codec, EnterpriseInvocation invocation, String attempt, long now) {
        var evaluation = invocation.evaluation();
        String device = evaluation.context().deviceId();
        var entry = tx.load().entries().get(Kind.TOOL.id(evaluation.toolId()));
        if (entry == null || !entry.enabled()) return;
        var tool = codec.model(entry.document(), ControlTool.class);
        if (!tool.packageDigest().equals(evaluation.packageDigest())) return;
        String document = tx.packages().document(key(codec, tool.id(), tool.packageDigest()));
        if (document == null) return;
        var proof = (JsonNode) codec.value(document);
        if (!"native-file-qualification/v1".equals(proof.path("format").asText())
                || !tool.id().equals(proof.path("toolId").asText())
                || !tool.packageDigest().equals(proof.path("managedDigest").asText())) return;
        var configuration = tx.endpointConfiguration(device);
        if (configuration == null) return;
        boolean installed = codec.model(configuration.localTools(), LocalToolCatalog.class).tools().stream().anyMatch(item ->
            item.enabled() && item.toolId().equals(tool.id()) && item.packageDigest().equals(tool.packageDigest())
                && item.toolDigest().equals(evaluation.toolDigest()));
        if (!installed) return;
        for (var qualified : tx.nativeFiles().qualifications(device,now)) {
            var policy=qualified.value();
            if (!policy.imageDigest().equals(proof.path("imageDigest").asText())) continue;
            var group = tx.load().entries().get(Kind.DEVICE_GROUP.id(policy.deviceGroupId()));
            if (group == null || !group.enabled() || !codec.model(group.document(), ControlDeviceGroup.class).deviceIds().contains(device)) continue;
            tx.enterprise().saveRecoveryScope(attempt, new RecoveryAdmissionPolicy.Scope(device, policy.deviceGroupId(), policy.imageDigest(),
                proof.path("runtimeDigest").asText(), proof.path("descriptorDigest").asText(),
                Canonical.digest((JsonNode) codec.value(entry.document())), tool.revision()));
            var scope=new NativeFileStore.Scope(invocation.id(),attempt,qualified.id(),proof.path("writeAccess").asBoolean());
            tx.nativeFiles().admit(scope,codec.json(scope));
            return;
        }
    }
}
