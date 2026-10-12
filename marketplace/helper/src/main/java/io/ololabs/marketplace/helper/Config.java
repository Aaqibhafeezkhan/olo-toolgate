// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Helper settings, all from the environment (deploy/.env.example). */
public record Config(
        String marketplaceId,
        String token,
        Path signingKeyFile,
        String signingKeyId,
        String retiredKeys,
        Path toolgateCli,
        long maxPackageBytes,
        long maxExpandedBytes,
        Duration validationTimeout,
        int port,
        int threads,
        Set<String> reservedPrefixes,
        Set<String> systemPublishers,
        String s3Endpoint,
        String s3Region,
        String s3Bucket,
        String s3AccessKey,
        String s3SecretKey,
        Path fileRoot,
        String toolgateRevision) {

    public static final Set<String> DEFAULT_RESERVED = Set.of("olo", "ololabs", "toolgate", "examples");

    public static Config fromEnvironment(Map<String, String> env) {
        String token = secret(env, "MARKETPLACE_HELPER_TOKEN", "");
        if (token.length() < 16 || token.equals("change-me")) {
            throw new IllegalStateException("MARKETPLACE_HELPER_TOKEN must be set to a random value of at least 16 characters");
        }
        String root = env.get("MARKETPLACE_HELPER_FILE_ROOT");
        return new Config(
                required(env, "MARKETPLACE_ID"),
                token,
                Path.of(env.getOrDefault("MARKETPLACE_SIGNING_KEY_FILE", "/run/secrets/marketplace_signing_key")),
                required(env, "MARKETPLACE_SIGNING_KEY_ID"),
                env.getOrDefault("MARKETPLACE_RETIRED_KEYS", ""),
                Path.of(env.getOrDefault("TOOLGATE_CLI", "/opt/olo/bin/toolgate")),
                mib(env, "MARKETPLACE_MAX_PACKAGE_MB", 50),
                mib(env, "MARKETPLACE_MAX_EXPANDED_MB", 200),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("MARKETPLACE_VALIDATION_TIMEOUT_SECONDS", "30"))),
                Integer.parseInt(env.getOrDefault("MARKETPLACE_HELPER_PORT", "8080")),
                Integer.parseInt(env.getOrDefault("MARKETPLACE_HELPER_THREADS", "4")),
                set(env.get("MARKETPLACE_RESERVED_PREFIXES"), DEFAULT_RESERVED),
                set(env.get("MARKETPLACE_SYSTEM_PUBLISHERS"), Set.of("examples")),
                env.get("MARKETPLACE_S3_ENDPOINT"),
                env.getOrDefault("MARKETPLACE_S3_REGION", "us-east-1"),
                env.get("MARKETPLACE_S3_BUCKET"),
                secret(env, "MARKETPLACE_S3_ACCESS_KEY", null),
                secret(env, "MARKETPLACE_S3_SECRET_KEY", null),
                root == null || root.isBlank() ? null : Path.of(root),
                env.getOrDefault("TOOLGATE_REVISION", "unknown"));
    }

    /** A secret from {@code <name>_FILE} (Compose and Kubernetes secret mounts) when set, else from {@code <name>}. */
    static String secret(Map<String, String> env, String name, String fallback) {
        String file = env.get(name + "_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).strip();
            } catch (IOException e) {
                throw new IllegalStateException(name + "_FILE is not readable: " + file, e);
            }
        }
        return env.getOrDefault(name, fallback);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static long mib(Map<String, String> env, String name, long fallback) {
        return Long.parseLong(env.getOrDefault(name, Long.toString(fallback))) * 1024 * 1024;
    }

    private static Set<String> set(String value, Set<String> fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Set.copyOf(List.of(value.trim().split("\\s*,\\s*")));
    }
}
