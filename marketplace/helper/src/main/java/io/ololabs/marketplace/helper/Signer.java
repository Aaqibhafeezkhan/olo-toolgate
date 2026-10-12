// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ed25519 release signing (design §6.1). The private key is the PKCS#8 PEM that {@code scripts/init-signing-key.sh}
 * writes; the helper refuses to start without it and never generates one.
 */
public final class Signer {
    public static final String SCHEMA = "toolgate.marketplace.release/1";
    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the raw 32-byte key follows. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final String keyId;
    private final PrivateKey privateKey;
    private final byte[] rawPublicKey;
    private final List<Map<String, Object>> retired;

    public Signer(String keyId, PrivateKey privateKey, byte[] rawPublicKey, List<Map<String, Object>> retired) {
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.rawPublicKey = rawPublicKey;
        this.retired = retired;
    }

    public static Signer load(Path keyFile, String keyId, String retiredKeys) {
        byte[] pem;
        try {
            pem = Files.readAllBytes(keyFile);
        } catch (IOException e) {
            throw new IllegalStateException("signing key " + keyFile + " cannot be read; run scripts/init-signing-key.sh once");
        }
        try {
            String text = new String(pem, StandardCharsets.US_ASCII);
            if (!text.contains("-----BEGIN PRIVATE KEY-----")) {
                throw new IllegalStateException("signing key " + keyFile + " is not a PKCS#8 PEM private key");
            }
            String body = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] pkcs8 = Base64.getDecoder().decode(body);
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            // The PKCS#8 body ends with the 32-byte seed; the JDK derives the matching public key from it.
            byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
            KeyPair pair = fromSeed(seed);
            Signer signer = new Signer(keyId, key, raw(pair.getPublic()), parseRetired(retiredKeys));
            signer.selfTest();
            return signer;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("signing key " + keyFile + " is not a valid Ed25519 key", e);
        }
    }

    private static KeyPair fromSeed(byte[] seed) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
            }
        });
        return generator.generateKeyPair();
    }

    /** Signs and verifies a probe, so a key whose derived public half doesn't match fails at startup. */
    private void selfTest() throws GeneralSecurityException {
        byte[] probe = "olo-orgs-signing-self-test".getBytes(StandardCharsets.UTF_8);
        if (!verify(rawPublicKey, probe, sign(probe))) {
            throw new IllegalStateException("signing key self-test failed");
        }
    }

    static byte[] raw(PublicKey key) {
        byte[] encoded = key.getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    public String keyId() {
        return keyId;
    }

    public byte[] rawPublicKey() {
        return rawPublicKey.clone();
    }

    public static String fingerprint(byte[] rawPublicKey) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawPublicKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] sign(byte[] message) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    public static boolean verify(byte[] rawPublicKey, byte[] message, byte[] signatureBytes) {
        try {
            byte[] spki = new byte[X509_PREFIX.length + 32];
            System.arraycopy(X509_PREFIX, 0, spki, 0, X509_PREFIX.length);
            System.arraycopy(rawPublicKey, 0, spki, X509_PREFIX.length, 32);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update(message);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Builds and signs a release record over the RFC 8785 bytes of every member except {@code signature}. */
    public Map<String, Object> release(Map<String, Object> fields) {
        Map<String, Object> record = new LinkedHashMap<>(fields);
        record.put("keyId", keyId);
        try {
            record.put("signature", Base64.getEncoder().encodeToString(sign(Json.canonical(record))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return record;
    }

    /** Verifies a release record against a raw public key, as Control does (design §8.3 signature step). */
    public static boolean verifyRelease(Map<String, Object> record, byte[] rawPublicKey) {
        Object signature = record.get("signature");
        if (!(signature instanceof String s)) {
            return false;
        }
        Map<String, Object> unsigned = new LinkedHashMap<>(record);
        unsigned.remove("signature");
        try {
            return verify(rawPublicKey, Json.canonical(unsigned), Base64.getDecoder().decode(s));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The key set for {@code GET /keys} and the website's well-known keys file. */
    public Map<String, Object> keySet(String marketplaceId) {
        List<Object> keys = new ArrayList<>();
        keys.add(keyEntry(keyId, rawPublicKey, "current"));
        keys.addAll(retired);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("marketplace", marketplaceId);
        set.put("keys", keys);
        return set;
    }

    private static Map<String, Object> keyEntry(String id, byte[] raw, String status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("keyId", id);
        entry.put("algorithm", "Ed25519");
        entry.put("publicKey", Base64.getEncoder().encodeToString(raw));
        entry.put("fingerprint", fingerprint(raw));
        entry.put("status", status);
        return entry;
    }

    /** {@code MARKETPLACE_RETIRED_KEYS}: comma-separated {@code keyId:base64PublicKey}, still listed for old releases. */
    static List<Map<String, Object>> parseRetired(String value) {
        List<Map<String, Object>> keys = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return keys;
        }
        for (String item : value.trim().split("\\s*,\\s*")) {
            int colon = item.indexOf(':');
            byte[] raw = colon > 0 ? Base64.getDecoder().decode(item.substring(colon + 1)) : new byte[0];
            if (raw.length != 32) {
                throw new IllegalStateException("MARKETPLACE_RETIRED_KEYS entry is not keyId:base64 32-byte key: " + item);
            }
            keys.add(keyEntry(item.substring(0, colon), raw, "retired"));
        }
        return keys;
    }
}
