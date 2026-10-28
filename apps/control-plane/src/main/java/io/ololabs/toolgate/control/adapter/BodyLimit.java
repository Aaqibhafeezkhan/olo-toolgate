// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** The HTTP limit admits a 64 MiB package upload; every other request stays at 2 MiB and must declare its length. */
@ApplicationScoped
public class BodyLimit {
    static final long DEFAULT = 2L * 1024 * 1024;
    static final String UPLOADS = "/api/control/v1/packages/uploads";
    void install(@Observes io.vertx.ext.web.Router router) {
        router.route().order(-100).handler(BodyLimit::check);
    }
    static void check(RoutingContext context) {
        var request = context.request();
        if (UPLOADS.equals(request.path())) { context.next(); return; }
        String length = request.getHeader("Content-Length"), encoding = request.getHeader("Transfer-Encoding");
        boolean tooLarge;
        try { tooLarge = length != null ? Long.parseLong(length.trim()) > DEFAULT : encoding != null; }
        catch (NumberFormatException invalid) { tooLarge = true; }
        if (tooLarge) { context.response().setStatusCode(413).putHeader("Cache-Control", "no-store").end(); return; }
        context.next();
    }
}
