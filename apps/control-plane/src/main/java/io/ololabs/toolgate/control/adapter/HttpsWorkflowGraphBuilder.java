// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Installation-selected runtime only; mTLS, bounded replies, no redirects or inherited proxies. */
public final class HttpsWorkflowGraphBuilder implements WorkflowGraphPreparation.Builder {
    private final String origin;
    private final Codec codec;
    private final HttpClient client;
    private final Semaphore slots = new Semaphore(2);
    public HttpsWorkflowGraphBuilder(String origin, Codec codec, javax.net.ssl.SSLContext tls) {
        this.origin = EndpointService.origin(origin); this.codec = java.util.Objects.requireNonNull(codec);
        this.client = HttpClient.newBuilder().sslContext(java.util.Objects.requireNonNull(tls))
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                public java.util.List<Proxy> select(URI uri) { return java.util.List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
            }).build();
    }
    public WorkflowGraphBuildResult build(WorkflowGraphBuildRequest request) {
        if (!slots.tryAcquire()) throw Failure.unavailable();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        var subscriber = new Body();
        try {
            var message = HttpRequest.newBuilder(URI.create(origin + "/internal/runtime/v1/graphs:rebuild"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(codec.json(request))).build();
            future = client.sendAsync(message, info -> subscriber);
            var response = future.get(20, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].equalsIgnoreCase("application/json")) throw Failure.unavailable();
            return codec.model(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8), WorkflowGraphBuildResult.class);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw Failure.unavailable();
        } catch (ExecutionException | TimeoutException | IllegalArgumentException invalid) {
            throw Failure.unavailable();
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            subscriber.cancel(); slots.release();
        }
    }
    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private volatile java.util.concurrent.Flow.Subscription subscription;
        private volatile boolean cancelled;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value; if (cancelled) value.cancel(); else value.request(1);
        }
        public void onNext(java.util.List<java.nio.ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 8192) {
                    cancel(); result.completeExceptionally(new java.io.IOException("Graph receipt size limit")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        void cancel() { cancelled = true; var value = subscription; if (value != null) value.cancel(); }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
