// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Private terminal results encrypted under tenant custody, atomically with lease completion. */
public final class WorkflowRunResults {
    private final Codec codec;
    private final TenantVaultKeys keys;
    public WorkflowRunResults(Codec codec, TenantVaultKeys keys) { this.codec = codec; this.keys = keys; }

    private String id(String run) {
        return Canonical.digest((JsonNode) codec.value(codec.json(List.of("workflow-run-result/v1", run))));
    }
    private byte[] context(TenantId tenant, WorkflowRunStore.QueuedRun run, String state, long version) {
        return codec.json(List.of("toolgate/workflow-run-result/v1", tenant.value(), run.id(), run.profileDigest(), state, version))
            .getBytes(StandardCharsets.UTF_8);
    }
    public void save(Store.Session tx, TenantId tenant, WorkflowRunStore.QueuedRun run, String state, JsonNode result, String code) {
        var plain = JsonNodeFactory.instance.objectNode();
        plain.set("result", result);
        if (code == null) plain.putNull("code"); else plain.put("code", code);
        byte[] bytes = codec.json(plain).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32768) throw Failure.validation();
        var key = keys.current(tx);
        byte[] encryption = keys.encryption(tenant, key);
        try {
            if (tx.packages().document(id(run.id())) != null) throw Failure.conflict();
            String ciphertext = VaultEncryption.encrypt(encryption, bytes, context(tenant, run, state, key.version()));
            tx.packages().saveDocument(id(run.id()), codec.json(Map.of("keyVersion", key.version(), "ciphertext", ciphertext)));
        } finally { Arrays.fill(bytes, (byte) 0); Arrays.fill(encryption, (byte) 0); }
    }
    public JsonNode open(Store.Session tx, TenantId tenant, WorkflowRunStore.QueuedRun run, String state) {
        String document = tx.packages().document(id(run.id()));
        if (document == null) return JsonNodeFactory.instance.nullNode();
        var sealed = (JsonNode) codec.value(document);
        if (!sealed.isObject() || sealed.size() != 2 || !sealed.path("keyVersion").isIntegralNumber()
                || !sealed.path("ciphertext").isTextual()) throw Failure.unavailable();
        long version = sealed.path("keyVersion").longValue();
        byte[] encryption = keys.encryption(tenant, tx.enterprise().vaultKey(version));
        byte[] bytes = null;
        try {
            bytes = VaultEncryption.decrypt(encryption, sealed.path("ciphertext").textValue(), context(tenant, run, state, version));
            return (JsonNode) codec.value(new String(bytes, StandardCharsets.UTF_8));
        } finally { Arrays.fill(encryption, (byte) 0); if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
}
