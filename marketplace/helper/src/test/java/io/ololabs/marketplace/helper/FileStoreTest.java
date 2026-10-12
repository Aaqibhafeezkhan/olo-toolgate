// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

final class FileStoreTest {
    private FileStoreTest() {
    }

    private static Config s3(String endpoint, long maxBytes) throws Exception {
        return new Config("m", "test-token-0123456789", Fixtures.keyFile(), "k", "", Fixtures.cli(), maxBytes,
                maxBytes, Duration.ofSeconds(5), 0, 1, Config.DEFAULT_RESERVED, Set.of("examples"), endpoint,
                "us-east-1", "uploads", "AKIDEXAMPLE", "secret", null, "r");
    }

    public static void testS3ReadIsSignedAndBucketBound() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getRawPath());
            byte[] body = exchange.getRequestURI().getRawPath().endsWith("missing") ? new byte[0] : "package bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body.length == 0 ? 404 : 200, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            FileStore store = new FileStore(s3(endpoint, 1024));
            Check.equal("package bytes", new String(store.read("s3://uploads/private/acme notify/1.tgpkg"), StandardCharsets.UTF_8));
            Check.equal("/uploads/private/acme%20notify/1.tgpkg", path.get());
            Check.that(auth.get().matches("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/\\d{8}/us-east-1/s3/aws4_request, "
                    + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=[0-9a-f]{64}"), auth.get());
            Check.refused("NOT_FOUND", () -> store.read("s3://uploads/missing"));
            Check.refused("INVALID_REQUEST", () -> store.read("s3://other-bucket/x.tgpkg"));
            Check.refused("INVALID_REQUEST", () -> store.read("s3://uploads/../x.tgpkg"));
            Check.refused("SIZE", () -> new FileStore(s3(endpoint, 4)).read("s3://uploads/x.tgpkg"));
            // file: references are off unless a file root is configured.
            Check.refused("INVALID_REQUEST", () -> store.read("file:x.tgpkg"));
            Check.refused("INVALID_REQUEST", () -> store.read("https://example.com/x.tgpkg"));
        } finally {
            server.stop(0);
        }
    }

    public static void testFileRootContainment() throws Exception {
        Path root = Fixtures.tempDir();
        Files.writeString(root.resolve("a.tgpkg"), "a");
        Path outside = Fixtures.tempDir().resolve("secret");
        Files.writeString(outside, "secret");
        Files.createSymbolicLink(root.resolve("link.tgpkg"), outside);
        FileStore store = new FileStore(Fixtures.config(root));
        Check.equal("a", new String(store.read("file:a.tgpkg"), StandardCharsets.UTF_8));
        Check.refused("INVALID_REQUEST", () -> store.read("file:../" + outside.getParent().getFileName() + "/secret"));
        Check.refused("INVALID_REQUEST", () -> store.read("file:link.tgpkg"));
        Check.refused("INVALID_REQUEST", () -> store.read("file:" + outside));
    }
}
