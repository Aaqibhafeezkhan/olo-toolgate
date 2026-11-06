// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Path("/api/control/v1/access/target-permits/{target}") @Produces("application/json") @Blocking
@io.quarkus.security.Authenticated
public class TargetClaimResource {
    @Inject PostgresStore store;@Inject ContractCodec codec;@Inject EnterpriseOperations operations;@Inject VerifiedActors actors;
    private TargetClaims claims(){return new TargetClaims(store,codec,operations);}
    private DirectoryService.Actor actor(String target){
        if(target==null||!target.matches("[a-z][a-z0-9-]{0,63}"))throw ManagementAccess.denied();
        return actors.service("toolgate-target-"+target);
    }
    private Response reply(Store.Reply r){return Response.status(r.status()).header("Cache-Control","no-store").entity(r.body()).build();}
    @POST @Path("claims/consume") @Consumes("application/json")
    public Response consume(@PathParam("target")String target,String body){return reply(claims().consume(actor(target),target,body));}
    @POST @Path("claims/status") @Consumes("application/json")
    public Response status(@PathParam("target")String target,String body){return reply(claims().inspect(actor(target),target,body));}
    @POST @Path("claims/outcome") @Consumes("application/json")
    public Response outcome(@PathParam("target")String target,String body){return reply(claims().outcome(actor(target),target,body));}
    @GET @Path("jwks")
    public Response keys(@PathParam("target")String target){actor(target);return Response.ok(codec.json(claims().keys())).header("Cache-Control","no-store").build();}
}
