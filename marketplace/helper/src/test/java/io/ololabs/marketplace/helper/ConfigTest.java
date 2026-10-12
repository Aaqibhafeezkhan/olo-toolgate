// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ConfigTest {
    private ConfigTest() {
    }

    public static void testSecretsComeFromFilesFirst() throws Exception {
        Path token = Files.createTempFile("helper-token", "");
        try {
            Files.writeString(token, "token-from-file-0123456789\n");
            Config config = Config.fromEnvironment(Map.of(
                    "MARKETPLACE_ID", "test-marketplace",
                    "MARKETPLACE_SIGNING_KEY_ID", "k1",
                    "MARKETPLACE_HELPER_TOKEN", "token-from-variable-0123",
                    "MARKETPLACE_HELPER_TOKEN_FILE", token.toString()));
            Check.equal("token-from-file-0123456789", config.token());
        } finally {
            Files.deleteIfExists(token);
        }
    }

    public static void testUnreadableSecretFileFails() {
        Check.fails(IllegalStateException.class, () -> Config.fromEnvironment(Map.of(
                "MARKETPLACE_ID", "test-marketplace",
                "MARKETPLACE_SIGNING_KEY_ID", "k1",
                "MARKETPLACE_HELPER_TOKEN_FILE", "/nonexistent/helper-token")));
    }
}
