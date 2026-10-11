// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import io.ololabs.toolgate.protocol.IdentityVerifier;
import io.ololabs.toolgate.protocol.IntrospectionVerifier;
import io.ololabs.toolgate.protocol.JwtVerifier;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Builds the identity verifier (adapter A4) from {@code TOOLGATE_AUTH_*} (runtime-modes.md §4). */
final class Verifiers {
    static final List<String> SOURCES = List.of("TOOLGATE_AUTH_JWKS_URL", "TOOLGATE_AUTH_KEYS_FILE", "TOOLGATE_AUTH_INTROSPECTION_URL");
    static final String DEFAULT_ALGORITHMS = "RS256,ES256";

    private Verifiers() {}

    /** Whether any verifier setting is present; development mode refuses to start with one. */
    static boolean configured(Map<String, String> env) {
        if (set(env, "TOOLGATE_AUTH_ISSUER")) return true;
        for (String s : SOURCES) if (set(env, s)) return true;
        return false;
    }

    /** A short description for the startup banner. */
    static String describe(Map<String, String> env) {
        if (set(env, "TOOLGATE_AUTH_INTROSPECTION_URL")) return "RFC 7662 introspection at " + env.get("TOOLGATE_AUTH_INTROSPECTION_URL").trim();
        return "JWTs from " + env.get("TOOLGATE_AUTH_ISSUER").trim() + " for audience " + env.get("TOOLGATE_AUTH_AUDIENCE").trim();
    }

    /**
     * The verifier, or null when none is configured.
     *
     * @throws StartupRefused with {@code CONFIGURATION} for an incomplete or unsafe configuration
     */
    static IdentityVerifier fromEnv(Map<String, String> env) {
        int sources = 0;
        for (String s : SOURCES) if (set(env, s)) sources++;
        if (sources == 0) {
            if (configured(env) || set(env, "TOOLGATE_AUTH_AUDIENCE") || set(env, "TOOLGATE_AUTH_ALGORITHMS")) {
                throw refused("TOOLGATE_AUTH_ISSUER needs TOOLGATE_AUTH_JWKS_URL or TOOLGATE_AUTH_KEYS_FILE, or set TOOLGATE_AUTH_INTROSPECTION_URL");
            }
            return null;
        }
        if (sources > 1) throw refused("set exactly one of " + String.join(", ", SOURCES));
        String issuer = get(env, "TOOLGATE_AUTH_ISSUER");
        String audience = get(env, "TOOLGATE_AUTH_AUDIENCE");
        try {
            if (set(env, "TOOLGATE_AUTH_INTROSPECTION_URL")) {
                if (set(env, "TOOLGATE_AUTH_ALGORITHMS")) throw refused("TOOLGATE_AUTH_ALGORITHMS applies to JWTs, not to introspection");
                return IntrospectionVerifier.create(URI.create(get(env, "TOOLGATE_AUTH_INTROSPECTION_URL")), get(env, "TOOLGATE_AUTH_CLIENT_ID"),
                        get(env, "TOOLGATE_AUTH_CLIENT_SECRET"), issuer, audience);
            }
            if (issuer == null || audience == null) throw refused("JWT verification needs TOOLGATE_AUTH_ISSUER and TOOLGATE_AUTH_AUDIENCE");
            JwtVerifier.Keys keys;
            if (set(env, "TOOLGATE_AUTH_JWKS_URL")) {
                keys = JwtVerifier.jwks(URI.create(get(env, "TOOLGATE_AUTH_JWKS_URL")));
            } else {
                Path file = Path.of(get(env, "TOOLGATE_AUTH_KEYS_FILE"));
                try {
                    keys = JwtVerifier.pinned(Files.readString(file));
                } catch (IOException e) {
                    throw refused("cannot read TOOLGATE_AUTH_KEYS_FILE " + file);
                }
            }
            String algorithms = set(env, "TOOLGATE_AUTH_ALGORITHMS") ? env.get("TOOLGATE_AUTH_ALGORITHMS") : DEFAULT_ALGORITHMS;
            return new JwtVerifier(issuer, audience, JwtVerifier.algorithms(algorithms), keys, System::currentTimeMillis);
        } catch (IllegalArgumentException e) {
            throw refused(e.getMessage());
        }
    }

    private static StartupRefused refused(String message) {
        return new StartupRefused("CONFIGURATION", "identity verifier: " + message);
    }

    private static boolean set(Map<String, String> env, String name) {
        String v = env.get(name);
        return v != null && !v.isBlank();
    }

    private static String get(Map<String, String> env, String name) {
        return set(env, name) ? env.get(name).trim() : null;
    }
}
