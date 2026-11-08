// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.AuditAnchors;
import io.ololabs.toolgate.control.application.AuditChain;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** ADR 062 anchor custody: dedicated strong key, never shared with another Control key domain. */
final class AuditAnchorSchedulerTest {
    @Test void anchorKeyMustBeStrongAndDedicated() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
        var pair = generator.generateKeyPair(); var other = generator.generateKeyPair().getPublic();
        var signer = new AuditAnchorScheduler.RsaAnchorSigner("anchor-1", (RSAPrivateCrtKey) pair.getPrivate());
        var modulus = ((RSAPublicKey) pair.getPublic()).getModulus(); var foreign = ((RSAPublicKey) other).getModulus();
        // Exactly one custody entry (its own configured path) may carry the anchor modulus.
        assertDoesNotThrow(() -> AuditAnchorScheduler.requireDedicated(modulus, List.of(foreign, modulus)));
        assertThrows(IllegalArgumentException.class, () -> AuditAnchorScheduler.requireDedicated(modulus, List.of(foreign, modulus, modulus)));
        assertThrows(IllegalArgumentException.class, () -> AuditAnchorScheduler.requireDedicated(modulus, List.of(foreign)));

        var encoder = Base64.getUrlEncoder().withoutPadding();
        var keyring = AuditAnchors.keyring("{\"formatVersion\":1,\"purpose\":\"AUDIT_ANCHOR\",\"keys\":[{\"keyId\":\"anchor-1\",\"n\":\""
            + encoder.encodeToString(unsigned(modulus)) + "\",\"e\":\"AQAB\",\"state\":\"ACTIVE\",\"activatedAtUnixMs\":0,\"retiredAtUnixMs\":null}]}");
        var anchor = AuditAnchors.sign(signer, "11111111-1111-4111-8111-111111111111", new AuditChain.Head(1, "a".repeat(64)), 10);
        assertEquals(1, AuditAnchors.verify(anchor, keyring, "11111111-1111-4111-8111-111111111111").chainIndex());

        generator.initialize(2048);
        var weak = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
        assertThrows(IllegalArgumentException.class, () -> new AuditAnchorScheduler.RsaAnchorSigner("anchor-weak", weak));
    }
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray(); return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
