// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Encrypted run input bound to one tenant, run and pinned configuration. */
public final class WorkflowRunInputs {
    private final Codec codec;
    private final TenantVaultKeys keys;

    public WorkflowRunInputs(Codec codec, TenantVaultKeys keys) {
        this.codec = codec;
        this.keys = keys;
    }

    public record Sealed(long keyVersion, String ciphertext) {
        @Override public String toString() { return "Sealed[keyVersion=" + keyVersion + "]"; }
    }

    public Sealed seal(Store.Session tx, TenantId tenant, String runId, String profileDigest, String message) {
        var key = keys.current(tx);
        byte[] plaintext = message.getBytes(StandardCharsets.UTF_8);
        byte[] encryption = null;
        try {
            if (plaintext.length == 0 || plaintext.length > 32768) throw Failure.validation();
            encryption = keys.encryption(tenant, key);
            return new Sealed(key.version(), VaultEncryption.encrypt(encryption, plaintext,
                context(tenant, runId, profileDigest, key.version())));
        } finally {
            if (encryption != null) Arrays.fill(encryption, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String open(Store.Session tx, TenantId tenant, WorkflowRunStore.QueuedRun run) {
        byte[] encryption = keys.encryption(tenant, tx.enterprise().vaultKey(run.keyVersion()));
        byte[] plaintext = null;
        try {
            plaintext = VaultEncryption.decrypt(encryption, run.document(), context(tenant, run.id(), run.profileDigest(), run.keyVersion()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(encryption, (byte) 0);
        }
    }

    private byte[] context(TenantId tenant, String runId, String digest, long keyVersion) {
        return codec.json(List.of("toolgate/workflow-run-input/v1", tenant.value(), runId, digest, keyVersion))
            .getBytes(StandardCharsets.UTF_8);
    }
}
