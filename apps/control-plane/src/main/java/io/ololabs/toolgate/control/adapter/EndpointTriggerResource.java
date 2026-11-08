// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/**
 * The device's trigger routes (plan 8.1), authenticated by the device certificate over direct TLS.
 * <p>
 * They have their own root resource: JAX-RS selects the root class by the longest literal {@code @Path} before it
 * matches methods, so under {@code /api/control/v1} they were shadowed by {@link EndpointResource}
 * ({@code /api/control/v1/endpoint}) and every device got 404.
 */
@Path("/api/control/v1/endpoint/triggers") @Produces("application/json") @Blocking @PermitAll
public class EndpointTriggerResource {
    @Inject TriggerService service; @Inject RoutingContext routing;

    private java.security.cert.X509Certificate peer() {
        if (!routing.request().isSSL()) throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Direct TLS required");
        try {
            var certificates = routing.request().sslSession().getPeerCertificates();
            if (certificates.length == 0) throw new IllegalArgumentException();
            return (java.security.cert.X509Certificate) certificates[0];
        } catch (Exception failure) { throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Device certificate required"); }
    }
    private static Response response(Store.Reply reply) {
        var builder = Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body());
        if (reply.revision() > 0) builder.header("ETag", "W/\"" + reply.revision() + "\"");
        return builder.build();
    }

    @GET @Path("desired")
    public Response desired() { return response(service.desired(peer())); }

    @GET @Path("descriptors/{digest}")
    public Response descriptor(@PathParam("digest") String digest) { return response(service.descriptor(peer(), digest)); }
}
