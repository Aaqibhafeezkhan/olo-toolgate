// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import io.vertx.ext.web.RoutingContext;

/** Dedicated Gateway role and direct client mTLS keep relay assertions out of agent-controlled input. */
@Path("/api/control/v1/mcp") @Produces("application/json") @Blocking @PermitAll
public class McpResource {
    @Inject EndpointService endpoints;@Inject DirectoryService directory;@Inject RoutingContext routing;@Inject Correlation correlation;
    @Inject VerifiedActors actors;
    private DirectoryService.Actor actor(){return actors.management();}

    private java.security.cert.X509Certificate peer(){if(!routing.request().isSSL())throw new Failure(ErrorCode.UNAUTHORIZED,401,"Direct TLS required");try{return (java.security.cert.X509Certificate)routing.request().sslSession().getPeerCertificates()[0];}catch(Exception failure){throw new Failure(ErrorCode.UNAUTHORIZED,401,"Device certificate required");}}
    private String body(String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();return value;}
    private Response response(Store.Reply reply){return Response.status(reply.status()).header("Cache-Control","no-store").entity(reply.body()).build();}
    @POST @Path("catalog") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway") public Response catalog(String value){var reply=endpoints.relay().catalog(actors.service("toolgate-relay-gateway"),true,body(value));return Response.status(reply.status()).header("Cache-Control","no-store").header("Toolgate-Authorization-Epoch",reply.revision()).entity(reply.body()).build();}
    @POST @Path("requests") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway") public Response submit(String value){return response(endpoints.relay().submit(actors.service("toolgate-relay-gateway"),true,body(value),correlation.id()));}
    @POST @Path("responses") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway") public Response get(String value){return response(endpoints.relay().response(actors.service("toolgate-relay-gateway"),true,body(value),correlation.id()));}
    @POST @Path("authorize") @Consumes("application/json") public Response authorize(String value){return response(endpoints.relay().authorize(peer(),body(value)));}
    @POST @Path("results") @Consumes("application/json") public Response result(String value){return response(endpoints.relay().result(peer(),body(value),correlation.id()));}
    @GET @Path("requests") @io.quarkus.security.Authenticated public Response page(){return response(endpoints.relay().page(actor(),correlation.id()));}
    @GET @Path("requests/{id}") @io.quarkus.security.Authenticated public Response inspect(@PathParam("id") String requestId){return response(endpoints.relay().inspect(actor(),requestId));}
}
