// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsRebuildRequest;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.InsightsService;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/** The installation-operator Insights API (ADR 031 §8). Its own listener, off unless configured, separate from tenant
 *  ingress and the probe port. TLS requires a client certificate issued by the operator CA; no tenant credential is
 *  accepted here. Every request is audited with the operator certificate's subject. */
@ApplicationScoped
public class InsightsOperatorListener {
    private static final Logger AUDIT = Logger.getLogger("toolgate.audit.insights");
    @Inject Vertx vertx; @Inject Config config; @Inject InsightsService insights; @Inject ContractCodec codec;
    private HttpServer server;

    void start(@Observes StartupEvent event) {
        var port = config.getOptionalValue("toolgate.control.insights.operator.port", Integer.class);
        if (port.isEmpty()) return;
        listen(config.getOptionalValue("toolgate.control.insights.operator.host", String.class).orElse("127.0.0.1"), port.get(),
            config.getValue("toolgate.control.insights.operator.certificate-path", String.class),
            config.getValue("toolgate.control.insights.operator.private-key-path", String.class),
            config.getValue("toolgate.control.insights.operator.client-ca-path", String.class));
    }
    void listen(String host, int port, String certificate, String key, String clientCa) {
        var options = new HttpServerOptions().setHost(host).setPort(port).setSsl(true).setClientAuth(ClientAuth.REQUIRED)
            .setKeyCertOptions(new PemKeyCertOptions().setCertPath(certificate).setKeyPath(key))
            .setTrustOptions(new PemTrustOptions().addCertPath(clientCa))
            .setIdleTimeout(30);
        try {
            server = vertx.createHttpServer(options).requestHandler(router()).listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception failure) { throw new IllegalStateException("The Insights operator listener could not start", failure); }
    }
    public int port() { return server == null ? -1 : server.actualPort(); }

    Router router() {
        var router = Router.router(vertx);
        router.route().handler(ctx -> {
            if (operator(ctx) == null) { ctx.response().setStatusCode(401).end(); return; }
            ctx.next();
        });
        router.get("/").handler(ctx -> ctx.response().setStatusCode(302).putHeader("Location", "/console/insights.html").putHeader("Cache-Control", "no-store").end());
        router.get("/console/*").blockingHandler(this::asset, false);
        router.get("/insights/v1/status").blockingHandler(ctx -> respond(ctx, () -> insights.status(System.currentTimeMillis())), false);
        router.get("/insights/v1/series").blockingHandler(ctx -> respond(ctx, () -> {
            var granularity = InsightsGranularity.valueOf(required(ctx, "granularity"));
            return insights.series(granularity, number(ctx, "from"), number(ctx, "to"), param(ctx, "groupBy"), param(ctx, "tenant"), param(ctx, "tool"),
                param(ctx, "client"), System.currentTimeMillis());
        }), false);
        router.get("/insights/v1/invocations").blockingHandler(ctx -> respond(ctx, () -> insights.invocations(param(ctx, "tenant"), number(ctx, "from"),
            number(ctx, "to"), param(ctx, "tool"), param(ctx, "client"), param(ctx, "outcome"), param(ctx, "search"), param(ctx, "after"),
            param(ctx, "limit") == null ? 50 : (int) number(ctx, "limit"))), false);
        router.get("/insights/v1/invocations/:tenant/:id").blockingHandler(ctx -> respond(ctx,
            () -> insights.detail(ctx.pathParam("tenant"), ctx.pathParam("id"))), false);
        router.get("/insights/v1/sessions").blockingHandler(ctx -> respond(ctx, () -> insights.sessions(required(ctx, "tenant"), number(ctx, "from"),
            number(ctx, "to"), param(ctx, "after"), param(ctx, "limit") == null ? 50 : (int) number(ctx, "limit"))), false);
        router.get("/insights/v1/sessions/timeline").blockingHandler(ctx -> respond(ctx,
            () -> insights.session(required(ctx, "tenant"), required(ctx, "session"))), false);
        router.post("/insights/v1/rebuild").handler(BodyHandler.create().setBodyLimit(4096));
        router.post("/insights/v1/rebuild").blockingHandler(ctx -> respond(ctx, () -> insights.rebuild(
            codec.model(ctx.body().asString(), InsightsRebuildRequest.class), System.currentTimeMillis())), false);
        return router;
    }

    /** Only the page and its hashed build assets; nothing else of the console is reachable here. */
    private static final java.util.regex.Pattern ASSET = java.util.regex.Pattern.compile("insights[.]html|assets/[A-Za-z0-9_-]+([.][A-Za-z0-9_-]+)*[.](js|css|svg|png|woff2)");
    private static final java.util.Map<String, String> TYPES = java.util.Map.of("html", "text/html; charset=UTF-8", "js", "text/javascript; charset=UTF-8",
        "css", "text/css; charset=UTF-8", "svg", "image/svg+xml", "png", "image/png", "woff2", "font/woff2");
    private void asset(RoutingContext ctx) {
        String path = ctx.normalizedPath().substring("/console/".length());
        byte[] body = null;
        if (ASSET.matcher(path).matches()) try (var input = InsightsOperatorListener.class.getResourceAsStream("/META-INF/resources/console/" + path)) {
            if (input != null) body = input.readAllBytes();
        } catch (java.io.IOException unreadable) { body = null; }
        var response = ctx.response();
        response.putHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
            .putHeader("X-Content-Type-Options", "nosniff").putHeader("Referrer-Policy", "no-referrer");
        if (body == null) { response.setStatusCode(404).putHeader("Cache-Control", "no-store").end(); return; }
        response.putHeader("Content-Type", TYPES.get(path.substring(path.lastIndexOf('.') + 1)))
            .putHeader("Cache-Control", path.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-store")
            .end(io.vertx.core.buffer.Buffer.buffer(body));
    }

    /** The verified operator certificate's subject, or null. TLS already refused clients the operator CA did not sign. */
    static String operator(RoutingContext ctx) {
        try {
            if (!ctx.request().isSSL()) return null;
            var chain = ctx.request().connection().peerCertificates();
            return chain.isEmpty() || !(chain.get(0) instanceof X509Certificate certificate) ? null : certificate.getSubjectX500Principal().getName();
        } catch (javax.net.ssl.SSLPeerUnverifiedException unverified) { return null; }
    }
    private void respond(RoutingContext ctx, Supplier<Object> work) {
        String operator = operator(ctx); int status; String body;
        try { body = codec.json(work.get()); status = 200; }
        catch (Failure failure) { status = failure.status(); body = "{\"code\":\"" + failure.code() + "\"}"; }
        catch (IllegalArgumentException | NullPointerException invalid) { status = 400; body = "{\"code\":\"VALIDATION\"}"; }
        catch (RuntimeException unavailable) { status = 503; body = "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}"; }
        // Filters only, never search values: a search term may be a caller's identifier.
        AUDIT.infof("insights_operator_access subject=\"%s\" method=%s path=%s tenant=%s status=%d", operator, ctx.request().method(),
            ctx.normalizedPath(), param(ctx, "tenant") == null ? ctx.pathParam("tenant") : param(ctx, "tenant"), status);
        ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").putHeader("Cache-Control", "no-store").end(body);
    }
    private static String param(RoutingContext ctx, String name) {
        var values = ctx.queryParam(name);
        if (values.size() > 1) throw new IllegalArgumentException(name);
        return values.isEmpty() || values.get(0).isEmpty() ? null : values.get(0);
    }
    private static String required(RoutingContext ctx, String name) { var v = param(ctx, name); if (v == null) throw new IllegalArgumentException(name); return v; }
    private static long number(RoutingContext ctx, String name) { return Long.parseLong(required(ctx, name)); }

    @PreDestroy void stop() { if (server != null) server.close(); }
}
