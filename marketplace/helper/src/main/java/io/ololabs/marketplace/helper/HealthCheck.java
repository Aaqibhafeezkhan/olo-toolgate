// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The container health check: exits 0 when {@code GET /health} answers 200. The image has no curl. */
public final class HealthCheck {
    private HealthCheck() {
    }

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("MARKETPLACE_HELPER_PORT", "8080");
        try {
            HttpResponse<Void> response = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding());
            System.exit(response.statusCode() == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
