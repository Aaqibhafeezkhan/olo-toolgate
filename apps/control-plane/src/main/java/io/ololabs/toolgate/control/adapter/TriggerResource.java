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
 * Trigger admin routes (plan 10.2) and the device's desired-state route (plan 8.1). Every reply carries the resource's
 * ETag and {@code Cache-Control: no-store}.
 */
@Path("/api/control/v1") @Produces("application/json") @Blocking @PermitAll
public class TriggerResource {
    @Inject TriggerService service; @Inject RoutingContext routing; @Inject Correlation correlation; @Inject VerifiedActors actors;

    private void tls() { if (!routing.request().isSSL()) throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Direct TLS required"); }
    private java.security.cert.X509Certificate peer() {
        tls();
        try {
            var certificates = routing.request().sslSession().getPeerCertificates();
            if (certificates.length == 0) throw new IllegalArgumentException();
            return (java.security.cert.X509Certificate) certificates[0];
        } catch (Exception failure) { throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Device certificate required"); }
    }
    private DirectoryService.Actor actor() { tls(); return actors.management(); }
    private static Response response(Store.Reply reply) {
        var builder = Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body());
        if (reply.revision() > 0) builder.header("ETag", "W/\"" + reply.revision() + "\"");
        return builder.build();
    }
    private static boolean dryRun(String value) {
        if (value == null || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw Failure.validation();
    }

    @GET @Path("triggers/definitions") @io.quarkus.security.Authenticated
    public Response definitions(@QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) { return response(service.definitions(actor(), token, size)); }

    @POST @Path("triggers/assignments") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response create(@HeaderParam("Idempotency-Key") String key, @QueryParam("dryRun") String dryRun, String body) {
        return response(service.create(actor(), body, key, dryRun(dryRun), correlation.id()));
    }

    @GET @Path("triggers/assignments") @io.quarkus.security.Authenticated
    public Response list(@QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size, @QueryParam("labelSelector") String selector) {
        return response(service.list(actor(), token, size, selector));
    }

    @GET @Path("triggers/assignments/{name}") @io.quarkus.security.Authenticated
    public Response get(@PathParam("name") String name) { return response(service.get(actor(), name)); }

    @PATCH @Path("triggers/assignments/{name}") @Consumes("application/merge-patch+json") @io.quarkus.security.Authenticated
    public Response patch(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch, @HeaderParam("Idempotency-Key") String key,
                          @QueryParam("dryRun") String dryRun, String body) {
        return response(service.patch(actor(), name, body, ifMatch, key, dryRun(dryRun), correlation.id()));
    }

    @DELETE @Path("triggers/assignments/{name}") @io.quarkus.security.Authenticated
    public Response delete(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch, @HeaderParam("Idempotency-Key") String key,
                           @QueryParam("dryRun") String dryRun) {
        return response(service.delete(actor(), name, ifMatch, key, dryRun(dryRun), correlation.id()));
    }

    @GET @Path("triggers/instances") @io.quarkus.security.Authenticated
    public Response instances(@QueryParam("assignment") String assignment, @QueryParam("executorId") String executorId,
                              @QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) {
        return response(service.instances(actor(), assignment, executorId, token, size));
    }

    @GET @Path("endpoint/triggers/desired")
    public Response desired() { return response(service.desired(peer())); }
}
