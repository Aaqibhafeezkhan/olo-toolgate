// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import io.vertx.ext.web.RoutingContext;

/** SDK authoring/building only. Test invocations use the governed invocation API. */
@Path("/api/control/v1/builder") @Produces("application/json") @Blocking @io.quarkus.security.Authenticated
public class BuilderResource {
    @Inject SdkBuilderService service;
    @Inject RoutingContext routing;
    @Inject Correlation correlation;
    @Inject VerifiedActors actors;
    private DirectoryService.Actor actor() {
        if (!routing.request().isSSL()) throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Direct TLS required");
        return actors.management();
    }
    private String body(String value) {
        if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) throw Failure.validation();
        return value;
    }
    private Response response(Store.Reply reply) {
        return Response.status(reply.status()).header("Cache-Control", "no-store").header("ETag", "\"" + reply.revision() + "\"").entity(reply.body()).build();
    }
    @GET @Path("drafts") public Response drafts(@QueryParam("cursor") String cursor) { return response(service.drafts(actor(), cursor)); }
    @GET @Path("status") public Response status() { return response(service.status(actor())); }
    @POST @Path("drafts") @Consumes("application/json") public Response save(@HeaderParam("Idempotency-Key") String key, String value) {
        return response(service.save(actor(), body(value), key, correlation.id()));
    }
    @POST @Path("sources") @Consumes("application/zip") public Response source(byte[] archive) {
        if (archive == null || archive.length > io.ololabs.toolgate.jsonsdk.JsonSdk.MAX_ARCHIVE_BYTES) throw Failure.validation();
        return response(service.source(actor(), archive));
    }
        @GET @Path("builds") public Response builds(@QueryParam("cursor") String cursor) { return response(service.builds(actor(), cursor)); }
    @POST @Path("builds") @Consumes("application/json") public Response build(@HeaderParam("Idempotency-Key") String key, String value) {
        return response(service.queue(actor(), body(value), key, correlation.id()));
    }
    private Response download(String id, boolean source) {
        return Response.ok(service.download(actor(), id, source), source ? "application/zip" : "application/vnd.ololabs.toolgate.package+zip")
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=\"" + (source ? "source.zip" : "package.tgpkg") + "\"").build();
    }
    @GET @Path("builds/{id}/package") public Response packageFile(@PathParam("id") String id) { return download(id, false); }
    @GET @Path("builds/{id}/source") public Response sourceFile(@PathParam("id") String id) { return download(id, true); }
}
