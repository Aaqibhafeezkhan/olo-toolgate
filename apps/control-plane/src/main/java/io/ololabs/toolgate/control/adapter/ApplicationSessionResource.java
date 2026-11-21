// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** A verified user may revoke their own sessions; no management role is required. */
@Path("/api/control/v1/application-session/revoke") @Produces("application/json") @io.quarkus.security.Authenticated @io.smallrye.common.annotation.Blocking
public class ApplicationSessionResource {
    @Inject VerifiedActors actors;@Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;@Inject PostgresStore store;@Inject ContractCodec codec;
    @POST @Consumes("application/json")public Response revoke(String body) {
        if(body==null||body.length()>1024)throw Failure.validation();
        var request=(com.fasterxml.jackson.databind.JsonNode)codec.value(body);if(!request.isObject()||!request.isEmpty())throw Failure.validation();
        var actor=actors.human();long now=System.currentTimeMillis()/1000*1000;
        if(!new IdentityIntake(store,codec).revokeSessions(actor.tenant(),jwt.getIssuer(),jwt.getSubject(),now,actor.id()))throw ManagementAccess.denied();
        return Response.noContent().header("Cache-Control","no-store").build();
    }
}
