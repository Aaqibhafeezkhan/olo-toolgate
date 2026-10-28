// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** The OLO Orgs Marketplace in ToolGate Admin (marketplace design §8): catalog, versions, detail and publisher for
 *  administrators with read access, import for the Super Admin, and the media proxy that keeps the console's
 *  {@code img-src 'self'}. Media is public Marketplace content and an {@code <img>} carries no bearer token, so the
 *  proxy needs none; it only answers for Marketplace media ids from the configured origin. */
@Path("/api/control/v1/marketplace") @Produces("application/json") @Blocking @PermitAll
public class MarketplaceResource {
    @Inject Marketplace marketplace; @Inject VerifiedActors actors; @Inject Correlation correlation;

    private static Response reply(Store.Reply reply) { return Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body()).build(); }
    private static String body(String value) { if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw Failure.validation(); return value; }

    @GET @io.quarkus.security.Authenticated public Response status() { return reply(marketplace.status(actors.management())); }

    @GET @Path("catalog") @io.quarkus.security.Authenticated
    public Response catalog(@QueryParam("page") Integer page, @QueryParam("limit") Integer limit, @QueryParam("q") String q,
                            @QueryParam("category") String category, @QueryParam("tag") String tag, @QueryParam("publisher") String publisher) {
        return reply(marketplace.catalog(actors.management(), page, limit, q, category, tag, publisher));
    }

    @GET @Path("packages/{packageId}") @io.quarkus.security.Authenticated
    public Response versions(@PathParam("packageId") String packageId) { return reply(marketplace.versions(actors.management(), packageId)); }

    @GET @Path("packages/{packageId}/{version}") @io.quarkus.security.Authenticated
    public Response detail(@PathParam("packageId") String packageId, @PathParam("version") String version) { return reply(marketplace.detail(actors.management(), packageId, version)); }

    @GET @Path("publishers/{slug}") @io.quarkus.security.Authenticated
    public Response publisher(@PathParam("slug") String slug) { return reply(marketplace.publisher(actors.management(), slug)); }

    @POST @Path("imports") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response install(String document) { return reply(marketplace.install(actors.management(), body(document), correlation.id())); }

    @GET @Path("media/{mediaId}") @Produces({"image/png", "image/jpeg"})
    public Response media(@PathParam("mediaId") String mediaId) {
        var media = marketplace.media(mediaId);
        return Response.ok(media.body()).type(media.mediaType()).header("Cache-Control", "private, max-age=300")
            .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'; sandbox").build();
    }
}
