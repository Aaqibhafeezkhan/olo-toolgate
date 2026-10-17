// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.PackageReleaseSigner;
import io.ololabs.toolgate.contracts.FleetSignatureHeader;
import io.ololabs.toolgate.contracts.FleetSignedDocument;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

/** RS256 compact JWS over exact FleetPackageDocument bytes, typed {@code toolgate-package-release+jws} (ADR 026). The key
 *  is the package-release authority's, never the organization fleet key; Control verifies both stay disjoint. */
public final class RsaPackageReleaseSigner implements PackageReleaseSigner {
    private final ContractCodec codec; private final RSAPrivateCrtKey key; private final String keyId;
    public RsaPackageReleaseSigner(ContractCodec codec, RSAPrivateCrtKey key, String keyId) {
        if (key.getModulus().bitLength() < 2048 || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))) throw new IllegalArgumentException("RSA-2048 or larger release key required");
        this.codec = codec; this.key = key; this.keyId = io.ololabs.toolgate.control.domain.Ids.valid(keyId);
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public FleetSignedDocument sign(byte[] document) {
        if (document.length < 1 || document.length > 32768) throw Failure.validation();
        var header = codec.json(new FleetSignatureHeader("RS256", "toolgate-package-release+jws", keyId));
        var message = encode(header.getBytes(StandardCharsets.UTF_8)) + "." + encode(document);
        try {
            var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key); signature.update(message.getBytes(StandardCharsets.US_ASCII));
            return new FleetSignedDocument(message + "." + encode(signature.sign()));
        } catch (GeneralSecurityException failure) { throw Failure.unavailable(); }
    }
    public java.math.BigInteger modulus() { return key.getModulus(); }
}
