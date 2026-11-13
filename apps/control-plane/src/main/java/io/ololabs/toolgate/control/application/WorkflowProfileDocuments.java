// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Private prompts/configuration use existing tenant vault custody, not plaintext catalog rows. */
public final class WorkflowProfileDocuments {
    private static final int CHUNK = 32768, MAX = 262144;
    private final Codec codec;
    private final TenantVaultKeys keys;
    public WorkflowProfileDocuments(Codec codec, TenantVaultKeys keys) {
        this.codec = java.util.Objects.requireNonNull(codec); this.keys = java.util.Objects.requireNonNull(keys);
    }
    public record Sealed(long keyVersion, String document) {}
    private byte[] context(TenantId tenant, String id, String digest, long version, int index, int total) {
        return codec.json(List.of("toolgate/workflow-profile/v1", tenant.value(), id, digest, version, index, total)).getBytes(StandardCharsets.UTF_8);
    }
    public Sealed seal(Store.Session tx, TenantId tenant, String id, String digest, String document) {
        byte[] plain = document.getBytes(StandardCharsets.UTF_8);
        if (plain.length < 1 || plain.length > MAX) throw Failure.validation();
        byte[] encryption = null;
        try {
            var key = keys.current(tx); encryption = keys.encryption(tenant, key);
            int total = (plain.length + CHUNK - 1) / CHUNK; var chunks = new ArrayList<String>();
            for (int index = 0; index < total; index++) {
                byte[] part = Arrays.copyOfRange(plain, index * CHUNK, Math.min(plain.length, (index + 1) * CHUNK));
                try { chunks.add(VaultEncryption.encrypt(encryption, part, context(tenant, id, digest, key.version(), index, total))); }
                finally { Arrays.fill(part, (byte)0); }
            }
            return new Sealed(key.version(), codec.json(Map.of("formatVersion", 1, "chunks", chunks)));
        } finally { if (encryption != null) Arrays.fill(encryption, (byte)0); Arrays.fill(plain, (byte)0); }
    }
    public String open(Store.Session tx, TenantId tenant, WorkflowProfileStore.Revision revision) {
        var value = (JsonNode)codec.value(revision.document());
        if (!value.isObject() || value.size() != 2 || !value.path("formatVersion").isIntegralNumber()
                || !value.path("formatVersion").canConvertToLong() || value.path("formatVersion").longValue() != 1 || !value.path("chunks").isArray()
                || value.path("chunks").isEmpty() || value.path("chunks").size() > MAX / CHUNK) throw Failure.unavailable();
        byte[] encryption = keys.encryption(tenant, tx.enterprise().vaultKey(revision.keyVersion()));
        byte[] combined = new byte[MAX]; int length = 0;
        try {
            var chunks = value.path("chunks");
            for (int index = 0; index < chunks.size(); index++) {
                if (!chunks.get(index).isTextual()) throw Failure.unavailable();
                byte[] part = VaultEncryption.decrypt(encryption, chunks.get(index).textValue(),
                    context(tenant, revision.profileId(), revision.digest(), revision.keyVersion(), index, chunks.size()));
                try {
                    if (part.length < 1 || part.length > CHUNK || (index < chunks.size() - 1 && part.length != CHUNK)) throw Failure.unavailable();
                    System.arraycopy(part, 0, combined, length, part.length); length += part.length;
                } finally { Arrays.fill(part, (byte)0); }
            }
            byte[] plain = Arrays.copyOf(combined, length);
            try {
                if (!Canonical.sha256(plain).equals(revision.digest())) throw Failure.unavailable();
                return new String(plain, StandardCharsets.UTF_8);
            } finally { Arrays.fill(plain, (byte)0); }
        } finally { Arrays.fill(encryption, (byte)0); Arrays.fill(combined, (byte)0); }
    }
}
