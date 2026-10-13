// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.control.application.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** The configured Marketplace origin over HTTPS (design §8.3): one fixed origin, no redirects, no inherited proxy,
 *  bounded concurrency, connect and total timeouts, and a body cap enforced while streaming. */
public final class HttpsMarketplaceSource implements MarketplaceSource {
    private static final Duration CONNECT = Duration.ofSeconds(5), TOTAL = Duration.ofSeconds(60);
    private final String origin;
    private final HttpClient client;
    private final Semaphore slots = new Semaphore(4);

    public HttpsMarketplaceSource(String origin, javax.net.ssl.SSLContext tls) {
        this.origin = EndpointService.origin(origin);
        var builder = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
            });
        if (tls != null) builder.sslContext(tls);
        client = builder.build();
    }

    @Override public Response get(String path, String accept, long maxBytes) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("#")) throw Failure.validation();
        if (!slots.tryAcquire()) throw Failure.unavailable();
        var body = new AtomicReference<Bounded>();
        try {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(TOTAL).header("Accept", accept).GET().build();
            var future = client.sendAsync(request, info -> { var subscriber = new Bounded(maxBytes); body.set(subscriber); return subscriber; });
            HttpResponse<byte[]> response;
            try { response = future.get(TOTAL.toMillis(), TimeUnit.MILLISECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof TooLarge) throw new Failure(ErrorCode.VALIDATION, 413, "Marketplace response too large");
                throw Failure.unavailable();
            } catch (TimeoutException failure) { future.cancel(true); var b = body.get(); if (b != null) b.cancel(); throw Failure.unavailable(); }
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            return new Response(response.statusCode(), type, response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw Failure.unavailable();
        } catch (IllegalArgumentException failure) {
            throw Failure.unavailable();
        } finally { slots.release(); }
    }

    private static final class TooLarge extends IOException { private static final long serialVersionUID = 1L; TooLarge() { super("Marketplace response too large"); } }

    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long limit; private Flow.Subscription subscription;
        Bounded(long limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + (long) chunk.remaining() > limit) { subscription.cancel(); result.completeExceptionally(new TooLarge()); return; }
                var data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        void cancel() { if (subscription != null) subscription.cancel(); }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
