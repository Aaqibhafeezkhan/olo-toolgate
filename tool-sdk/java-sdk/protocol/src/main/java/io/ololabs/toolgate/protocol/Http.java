// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The HTTPS calls the identity verifier makes: bounded in time and size, no redirects. */
final class Http {
    static final int MAX_BYTES = 1024 * 1024;
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    private Http() {}

    static String get(URI url) {
        return send(HttpRequest.newBuilder(url).timeout(TIMEOUT).header("Accept", "application/json").GET().build());
    }

    static String postForm(URI url, String authorization, String form) {
        return send(HttpRequest.newBuilder(url).timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build());
    }

    private static String send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " from " + request.uri());
                if (body.length > MAX_BYTES) throw new IllegalStateException("response from " + request.uri() + " is larger than 1 MiB");
                return new String(body, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach " + request.uri() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling " + request.uri(), e);
        }
    }
}
