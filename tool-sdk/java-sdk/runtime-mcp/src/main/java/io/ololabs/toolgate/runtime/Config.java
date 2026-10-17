// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Standalone configuration: {@code TOOLGATE_MODE}, environment values and {@code toolgate.dev.json} (runtime-modes.md §4, §5). */
final class Config {
    enum Mode {
        PRODUCTION, DEVELOPMENT, TEST,
        /** One invocation under a Tool Host, over tool protocol v2 (Governed): values and claims come from the host. */
        GOVERNED;

        /** Whether claims come from fixtures: {@code toolgate.dev.json} in development, the harness in test. */
        boolean fixtures() {
            return this == DEVELOPMENT || this == TEST;
        }

        /** Whether claims come from this configuration rather than a verified credential on the request. */
        boolean configuredClaims() {
            return fixtures() || this == GOVERNED;
        }

        /** Whether route calls go to a transport in place of the network: recorded exchanges in test, the broker governed. */
        boolean transported() {
            return this == TEST || this == GOVERNED;
        }
    }

    final Mode mode;
    final Map<String, String> env;
    final JsonNode devJson;
    io.ololabs.toolgate.sdk.ExecutionLocation location = io.ololabs.toolgate.sdk.ExecutionLocation.SERVER;
    java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> responses;
    String governedInvocationId;
    java.time.Instant governedDeadline;
    /** The device files area: the executor broker on a device; outside one, an in-memory area that starts empty. */
    io.ololabs.toolgate.sdk.context.DeviceFiles deviceFiles;
    final List<String> warnings = new ArrayList<>();

    private Config(Mode mode, Map<String, String> env, JsonNode devJson) {
        this.mode = mode;
        this.env = env;
        this.devJson = devJson;
        if (mode != Mode.GOVERNED) this.deviceFiles = new InMemoryDeviceFiles();
    }

    static Config load(Map<String, String> env, Path workingDirectory) {
        String raw = env.getOrDefault("TOOLGATE_MODE", "production").trim().toLowerCase(Locale.ROOT);
        Mode mode = switch (raw) {
            case "production" -> Mode.PRODUCTION;
            case "development" -> Mode.DEVELOPMENT;
            case "test" -> Mode.TEST;
            default -> throw new StartupRefused("CONFIGURATION", "TOOLGATE_MODE must be production or development, not " + raw);
        };
        Path file = env.containsKey("TOOLGATE_DEV_JSON") ? Path.of(env.get("TOOLGATE_DEV_JSON")) : workingDirectory.resolve("toolgate.dev.json");
        JsonNode dev = null;
        if (Files.isRegularFile(file)) {
            try {
                dev = new ObjectMapper().readTree(file.toFile());
            } catch (IOException e) {
                throw new StartupRefused("CONFIGURATION", file + " is not valid JSON");
            }
            if (dev == null || !dev.isObject()) throw new StartupRefused("CONFIGURATION", file + " must hold a JSON object");
        }
        Config config = new Config(mode, Map.copyOf(env), dev);
        if (dev != null && dev.has("claims") && !mode.fixtures()) {
            config.warnings.add("toolgate.dev.json has claims, which are read only with TOOLGATE_MODE=development; ignoring them");
        }
        return config;
    }

    /** The configuration of test mode: the harness fixtures, in the shape of {@code toolgate.dev.json}, and no environment. */
    static Config test(JsonNode fixtures) {
        return new Config(Mode.TEST, Map.of(), fixtures);
    }

    /**
     * The configuration of governed mode: {@code values} holds the {@code globals} and {@code claims} the Tool Host sent
     * in the tool input, and {@code secrets} redeems a declared secret through the host, or returns null.
     */
    static Config governed(JsonNode values, java.util.function.Function<String, JsonNode> secrets) {
        Config config = new Config(Mode.GOVERNED, Map.of(), values);
        config.secrets = secrets;
        return config;
    }

    private java.util.function.Function<String, JsonNode> secrets;

    /** A global from {@code TOOLGATE_GLOBAL_<NAME>} (as text, typed later) or the dev file; null when neither sets it. */
    JsonNode global(String name, ObjectMapper json) {
        String fromEnv = env.get("TOOLGATE_GLOBAL_" + name.toUpperCase(Locale.ROOT));
        if (fromEnv != null) return json.getNodeFactory().textNode(fromEnv);
        return devJson == null ? null : devJson.path("globals").get(name);
    }

    /** A secret from {@code TOOLGATE_SECRET_<NAME>} or the dev file; null when neither sets it. */
    JsonNode secret(String name, ObjectMapper json) {
        if (secrets != null) return secrets.apply(name);
        String fromEnv = env.get("TOOLGATE_SECRET_" + name.toUpperCase(Locale.ROOT));
        if (fromEnv != null) return json.getNodeFactory().textNode(fromEnv);
        return devJson == null ? null : devJson.path("secrets").get(name);
    }

    /** A destination host from {@code TOOLGATE_DESTINATION_<HOSTSETTING>} or the dev file; null when neither sets it. */
    String destination(String hostSetting) {
        String fromEnv = env.get("TOOLGATE_DESTINATION_" + hostSetting.toUpperCase(Locale.ROOT));
        if (fromEnv != null) return fromEnv;
        JsonNode v = devJson == null ? null : devJson.path("destinations").get(hostSetting);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    /** Fixture claims in development and test mode, and the host's claims in governed mode. */
    JsonNode claims() {
        return mode.configuredClaims() && devJson != null && devJson.path("claims").isObject() ? devJson.get("claims") : null;
    }
}
