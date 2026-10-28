// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.UserCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Loads OLO user-credential exchange (M3.4) from {@code toolgate.control.user-credential.*}. Disabled by default.
 *
 * <p>The signing key is dedicated: Control refuses to start if it is the effect key. The platform backend's assertion key
 * is one pinned public key, never fetched from a URL.
 */
@ApplicationScoped
public class UserCredentialBootstrap {
    private static final String PREFIX = "toolgate.control.user-credential.";

    @Inject Config config;
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    private volatile Optional<UserCredentials> credentials;

    void start(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        credentials();
    }

    /** The exchange, when {@code toolgate.control.user-credential.enabled=true}. */
    public Optional<UserCredentials> credentials() {
        if (credentials == null) {
            credentials = load();
        }
        return credentials;
    }

    private Optional<UserCredentials> load() {
        if (!config.getOptionalValue(PREFIX + "enabled", Boolean.class).orElse(false)) {
            return Optional.empty();
        }
        try {
            RSAPrivateCrtKey key = privateKey(Path.of(config.getValue(PREFIX + "private-key-path", String.class)));
            refuseEffectKey(key);
            String keyId = config.getValue(PREFIX + "key-id", String.class);
            var settings = new UserCredentials.Settings(
                    config.getOptionalValue(PREFIX + "issuer", String.class).orElse("toolgate-control"),
                    config.getOptionalValue(PREFIX + "audience", String.class).orElse("toolgate-gateway"),
                    config.getOptionalValue(PREFIX + "lifetime-seconds", Long.class).orElse(UserCredentials.MAX_LIFETIME_SECONDS),
                    config.getValue(PREFIX + "binding-id", String.class),
                    config.getValue(PREFIX + "subject.issuer", String.class),
                    config.getValue(PREFIX + "subject.audience", String.class),
                    config.getValue(PREFIX + "subject.key-id", String.class),
                    publicKey(Path.of(config.getValue(PREFIX + "subject.public-key-path", String.class))));
            return Optional.of(new UserCredentials(store, codec, settings, signer(key, keyId), java.time.Clock.systemUTC()));
        } catch (java.io.IOException | GeneralSecurityException | IllegalArgumentException | java.util.NoSuchElementException failure) {
            throw new IllegalStateException("Dedicated user-credential configuration required", failure);
        }
    }

    static UserCredentials.Signer signer(RSAPrivateCrtKey key, String keyId) {
        if (!java.util.Set.of(2048, 3072, 4096).contains(key.getModulus().bitLength())
                || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))) {
            throw new IllegalArgumentException("Invalid user-credential key");
        }
        return new UserCredentials.Signer() {
            public String keyId() {
                return keyId;
            }

            public String sign(byte[] signingInput) {
                try {
                    var signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(key);
                    signature.update(signingInput);
                    return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
                } catch (GeneralSecurityException failure) {
                    throw io.ololabs.toolgate.control.application.Failure.unavailable();
                }
            }
        };
    }

    private void refuseEffectKey(RSAPrivateCrtKey key) throws java.io.IOException, GeneralSecurityException {
        var effect = config.getOptionalValue("toolgate.control.effect.private-key-path", String.class).filter(v -> !v.isBlank());
        if (effect.isPresent() && java.nio.file.Files.isRegularFile(Path.of(effect.get()))
                && privateKey(Path.of(effect.get())).getModulus().equals(key.getModulus())) {
            throw new IllegalArgumentException("User-credential key must not be the effect key");
        }
    }

    private static RSAPrivateCrtKey privateKey(Path path) throws java.io.IOException, GeneralSecurityException {
        String pem = new String(ProtectedCustody.privateKey(path, 16384), StandardCharsets.US_ASCII);
        if (!pem.startsWith("-----BEGIN " + "PRIVATE KEY-----")) {
            throw new IllegalArgumentException("PKCS#8 private key required");
        }
        byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN " + "PRIVATE KEY-----", "")
                .replace("-----END " + "PRIVATE KEY-----", "").replaceAll("\\s", ""));
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static PublicKey publicKey(Path path) throws java.io.IOException, GeneralSecurityException {
        String pem = ProtectedCustody.text(path, 16384);
        if (!pem.contains("-----BEGIN PUBLIC KEY-----")) {
            throw new IllegalArgumentException("SubjectPublicKeyInfo PEM required");
        }
        byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", ""));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (key.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Assertion key must be at least 2048 bits");
        }
        return key;
    }
}
