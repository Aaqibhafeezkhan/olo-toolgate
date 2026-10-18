// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.Config;
import javax.net.ssl.SSLContext;

/** Environment-owned optional shared cache; malformed configuration fails at startup. */
final class DirectoryCacheConfig {
    private DirectoryCacheConfig() {}
    private static String value(Config config, String name, String fallback) {
        return config.getOptionalValue("toolgate.control.cache." + name, String.class).orElse(fallback);
    }
    static DirectoryCache create(Config config, ContractCodec codec) {
        String mode = value(config, "mode", "local");
        if (mode.equals("local")) return new DirectoryCache();
        if (!mode.equals("redis")) throw new IllegalArgumentException("toolgate.control.cache.mode must be local or redis");
        try {
            String host = value(config, "host", ""), namespace = value(config, "namespace", ""), username = value(config, "username", "default");
            int port = Integer.parseInt(value(config, "port", "6379"));
            int timeoutMs = Integer.parseInt(value(config, "timeout-ms", "500"));
            int ttlSeconds = Integer.parseInt(value(config, "ttl-seconds", "3600"));
            boolean tls = switch (value(config, "tls", "true")) {
                case "true" -> true; case "false" -> false; default -> throw new IllegalArgumentException();
            };
            boolean development = config.getOptionalValue("toolgate.control.development-mode", Boolean.class).orElse(false);
            if (!host.matches("[A-Za-z0-9.:-]{1,253}")
                    || !namespace.matches("[A-Za-z0-9][A-Za-z0-9:_-]{0,127}") || username.isBlank() || username.length() > 128
                    || port < 1 || port > 65535 || timeoutMs < 50 || timeoutMs > 2000 || ttlSeconds < 1 || ttlSeconds > 3600
                    || !tls && !development) throw new IllegalArgumentException();
            byte[] password = new String(ProtectedCustody.privateKey(Path.of(value(config, "password-path", "")), 4096), StandardCharsets.UTF_8)
                .stripTrailing().getBytes(StandardCharsets.UTF_8);
            byte[] key = java.util.Base64.getDecoder().decode(ProtectedCustody.privateText(Path.of(value(config, "encryption-key-path", "")), 128).strip());
            if (password.length == 0 || key.length != 32) throw new IllegalArgumentException();
            SSLContext context = tls ? SSLContext.getDefault() : null;
            String caPath = value(config, "ca-path", "");
            if (!caPath.isEmpty()) {
                if (!tls) throw new IllegalArgumentException();
                var certificates = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(caPath), 65536)));
                if (certificates.isEmpty()) throw new IllegalArgumentException();
                var store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); store.load(null);
                int index = 0; for (var certificate : certificates) store.setCertificateEntry("cache-" + index++, certificate);
                var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
                context = SSLContext.getInstance("TLS"); context.init(null, trust.getTrustManagers(), null);
            }
            return new DirectoryCache(new SharedDirectoryCache(new RedisCacheClient(host, port, username, password, context, timeoutMs), codec, namespace, key, ttlSeconds));
        } catch (Exception invalid) {
            // Never include credentials, paths, endpoint responses or underlying exception messages.
            throw new IllegalArgumentException("Invalid toolgate.control.cache configuration: require host, namespace, protected password/encryption-key files, valid limits and production TLS");
        }
    }
}
