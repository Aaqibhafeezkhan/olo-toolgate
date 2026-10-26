// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Static shell is public; every data request still crosses the signed-token API.
 * Browser policy forbids external script, framing, inline execution and API hosts.
 */
@ApplicationScoped
public class ConsoleAssets {
    @jakarta.inject.Inject org.eclipse.microprofile.config.Config config;
    void routes(@Observes Router router) {
        if (config.getOptionalValue("toolgate.quickstart.enabled", Boolean.class).orElse(false)) {
            final String shell;
            try (var input=ConsoleAssets.class.getResourceAsStream("/META-INF/resources/console/index.html")) {
                if(input==null)throw new java.io.IOException();
                shell=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("<head>","<head><meta name=\"toolgate-mode\" content=\"quickstart\">");
            } catch(java.io.IOException failure){throw new IllegalStateException("Quickstart console artifact unavailable");}
            for(var path:new String[]{"/console/","/console/index.html"})router.get(path).order(-900).handler(context->context.response().putHeader("Content-Type","text/html; charset=UTF-8").end(shell));
        }
        // The operator Insights page is served only by the operator listener (ADR 031 §8), never on tenant ingress.
        router.get("/console/insights.html").order(-1100).handler(context -> context.response().setStatusCode(404).putHeader("Cache-Control", "no-store").end());
        router.route("/console/*").order(-1000).handler(context -> {
            context.addHeadersEndHandler(ignored -> {
            var headers = context.response().headers();
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            headers.set("Cache-Control", context.normalizedPath().startsWith("/console/assets/") ? "public, max-age=31536000, immutable" : "no-store");
            });
            context.next();
        });
        for (var path : new String[]{"/", "/console"}) {
            router.get(path).handler(context -> {
                if (context.request().path().equals(path)) context.response().setStatusCode(302)
                    .putHeader("Location", "/console/").putHeader("Cache-Control", "no-store").end();
                else context.next();
            });
        }
    }
}
