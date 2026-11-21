// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** Authenticated registration, ownership verification and revocation of caller-owned callbacks. */
@Path("/api/control/v1/application-webhooks") @Produces("application/json") @io.quarkus.security.Authenticated @io.smallrye.common.annotation.Blocking
public class ApplicationWebhookResource {
    @Inject VerifiedActors actors;@Inject ApplicationIntegrationBootstrap integrations;@Inject ContractCodec codec;
    private Response reply(Store.Reply reply){return Response.status(reply.status()).header("Cache-Control","no-store").entity(reply.body()).build();}
    @POST @Consumes("application/json") public Response create(@HeaderParam("Idempotency-Key")String key,String body){
        if(body==null||body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw Failure.validation();
        var actor=actors.human();return reply(integrations.webhooks(actor.tenant()).create(actor,body,key));
    }
    @GET public Response list(@QueryParam("after")String after){var actor=actors.human();return reply(integrations.webhooks(actor.tenant()).list(actor,after));}
    @GET @Path("{id}")public Response get(@PathParam("id")String id){var actor=actors.human();return reply(integrations.webhooks(actor.tenant()).get(actor,id));}
    @DELETE @Path("{id}")public Response revoke(@PathParam("id")String id){var actor=actors.human();return reply(integrations.webhooks(actor.tenant()).revoke(actor,id));}
    @POST @Path("{id}/verify") @Consumes("application/json")public Response verify(@PathParam("id")String id,String body){
        if(body==null||body.length()>1024)throw Failure.validation();
        var request=(com.fasterxml.jackson.databind.JsonNode)codec.value(body);if(!request.isObject()||!request.isEmpty())throw Failure.validation();
        var actor=actors.human();return reply(integrations.webhooks(actor.tenant()).verify(actor,id));
    }
}
