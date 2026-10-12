// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

/** A running helper on a random port with a file root, called over HTTP as Drupal calls it. */
final class HelperClient implements AutoCloseable {
    record Response(int status, Map<String, Object> body) {
        String code() {
            return Json.str(body.get("code"));
        }
    }

    final Config config;
    final Signer signer;
    private final HelperServer server;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final String base;

    HelperClient(Path fileRoot) throws Exception {
        config = Fixtures.config(fileRoot);
        signer = Fixtures.signer(config);
        server = new HelperServer(config, new FileStore(config), new Inspector(config, new CliValidator(config), signer), signer);
        base = "http://127.0.0.1:" + server.start("127.0.0.1", 0);
    }

    Response get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    Response post(String path, Map<String, Object> body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    Response post(String path, Map<String, Object> body) throws Exception {
        return post(path, body, config.token());
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), Json.obj(Json.parse(response.body())));
    }

    @Override
    public void close() {
        server.stop();
    }
}
