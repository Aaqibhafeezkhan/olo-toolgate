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
    enum Mode { PRODUCTION, DEVELOPMENT, TEST }

    final Mode mode;
    final Map<String, String> env;
    final JsonNode devJson;
    final List<String> warnings = new ArrayList<>();

    private Config(Mode mode, Map<String, String> env, JsonNode devJson) {
        this.mode = mode;
        this.env = env;
        this.devJson = devJson;
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
        if (dev != null && dev.has("claims") && mode != Mode.DEVELOPMENT) {
            config.warnings.add("toolgate.dev.json has claims, which are read only with TOOLGATE_MODE=development; ignoring them");
        }
        return config;
    }

    /** A global from {@code TOOLGATE_GLOBAL_<NAME>} (as text, typed later) or the dev file; null when neither sets it. */
    JsonNode global(String name, ObjectMapper json) {
        String fromEnv = env.get("TOOLGATE_GLOBAL_" + name.toUpperCase(Locale.ROOT));
        if (fromEnv != null) return json.getNodeFactory().textNode(fromEnv);
        return devJson == null ? null : devJson.path("globals").get(name);
    }

    /** A secret from {@code TOOLGATE_SECRET_<NAME>} or the dev file; null when neither sets it. */
    JsonNode secret(String name, ObjectMapper json) {
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

    /** Fixture claims, only in development mode. */
    JsonNode claims() {
        return mode == Mode.DEVELOPMENT && devJson != null && devJson.path("claims").isObject() ? devJson.get("claims") : null;
    }
}
