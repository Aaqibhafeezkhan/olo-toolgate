// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Optional acceleration. Database versions are mandatory even on authenticated cache hits. */
final class SharedDirectoryCache {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_RECORDS = 512;
    private final RedisCacheClient client;
    private final ContractCodec codec;
    private final String namespace;
    private final SecretKeySpec key;
    private final int ttlSeconds;
    private final java.util.function.LongSupplier clock;
    private final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(SharedDirectoryCache.class);
    private final java.util.concurrent.atomic.AtomicLong nextWarning = new java.util.concurrent.atomic.AtomicLong();

    SharedDirectoryCache(RedisCacheClient client, ContractCodec codec, String namespace, byte[] key, int ttlSeconds) {
        this(client, codec, namespace, key, ttlSeconds, System::currentTimeMillis);
    }
    SharedDirectoryCache(RedisCacheClient client, ContractCodec codec, String namespace, byte[] key, int ttlSeconds,
                         java.util.function.LongSupplier clock) {
        if (key.length != 32) throw new IllegalArgumentException("Directory cache requires a 256-bit encryption key");
        this.client = client; this.codec = codec; this.namespace = namespace;
        this.key = new SecretKeySpec(key, "AES"); this.ttlSeconds = ttlSeconds; this.clock = clock;
    }

    String cacheKey(String tenant, DirectoryCache.Version version) {
        // Length-independent, delimiter-safe tenant identity; namespace separates databases/deployments.
        try {
            var tenantHash = java.security.MessageDigest.getInstance("SHA-256").digest(tenant.getBytes(StandardCharsets.UTF_8));
            return namespace + ":directory:v1:" + java.util.HexFormat.of().formatHex(tenantHash)
                + ":" + version.revision() + ":" + version.generation();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    Directory get(String tenant, DirectoryCache.Version version) {
        if (version.generation() < 0) return null;
        try {
            String cacheKey = cacheKey(tenant, version);
            byte[] value = client.get(cacheKey);
            return value == null ? null : decode(cacheKey, version, value);
        } catch (Exception unavailable) { warn(); return null; }
    }

    void put(String tenant, DirectoryCache.Entry entry) {
        if (entry.version.generation() < 0 || entry.directory.entries().size() > MAX_RECORDS) return;
        try {
            String cacheKey = cacheKey(tenant, entry.version);
            byte[] value = encode(cacheKey, entry.directory);
            if (value != null) client.set(cacheKey, value, ttlSeconds);
        } catch (Exception unavailable) { warn(); }
    }

    private void warn() {
        long now = System.currentTimeMillis(), next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + 60000))
            log.warn("Shared directory cache unavailable or invalid; using database-validated local cache and database reads");
    }

    byte[] encode(String cacheKey, Directory directory) throws java.io.IOException, java.security.GeneralSecurityException {
        var buffer = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(buffer)) {
            output.writeLong(clock.getAsLong() + ttlSeconds * 1000L);
            output.writeInt(directory.entries().size());
            for (var entry : directory.entries().values()) {
                output.writeUTF(entry.id().kind().name());
                byte[] document = entry.document().getBytes(StandardCharsets.UTF_8);
                if (buffer.size() + (long) document.length + 4 > RedisCacheClient.MAX_VALUE - 28) return null;
                output.writeInt(document.length); output.write(document);
            }
        }
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(cacheKey.getBytes(StandardCharsets.UTF_8));
        var result = new ByteArrayOutputStream(); result.write(nonce); result.write(cipher.doFinal(buffer.toByteArray()));
        return result.toByteArray();
    }

    Directory decode(String cacheKey, DirectoryCache.Version version, byte[] value)
            throws java.io.IOException, java.security.GeneralSecurityException {
        if (value.length < 40 || value.length > RedisCacheClient.MAX_VALUE) throw new java.io.IOException("Cache value limit exceeded");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, value, 0, 12));
        cipher.updateAAD(cacheKey.getBytes(StandardCharsets.UTF_8));
        try (var input = new DataInputStream(new ByteArrayInputStream(cipher.doFinal(value, 12, value.length - 12)))) {
            long expires = input.readLong(), now = clock.getAsLong();
            if (expires <= now || expires - now > ttlSeconds * 1000L) throw new java.io.IOException("Expired cache value");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new java.io.IOException("Cache record limit exceeded");
            var entries = new HashMap<Ids.RecordId, Directory.Entry>();
            for (int i = 0; i < count; i++) {
                var kind = Ids.Kind.valueOf(input.readUTF());
                int size = input.readInt();
                if (size < 0 || size > input.available()) throw new java.io.IOException("Invalid cache record");
                var entry = codec.entry(kind, new String(input.readNBytes(size), StandardCharsets.UTF_8));
                if (entries.put(entry.id(), entry) != null) throw new java.io.IOException("Duplicate cache record");
            }
            if (input.available() != 0) throw new java.io.IOException("Trailing cache data");
            return new Directory(version.revision(), entries);
        }
    }
}
