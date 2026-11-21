// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** Structural status only: no trigger configuration or source payloads in the public projection. */
@Path("/api/control/v1/application-trigger-status") @Produces("application/json") @io.quarkus.security.Authenticated @io.smallrye.common.annotation.Blocking
public class ApplicationTriggerResource {
    @Inject VerifiedActors actors;@Inject ApplicationIntegrationBootstrap integrations;
    @GET @Path("{name}")public Response get(@PathParam("name")String name){var actor=actors.human();var reply=integrations.events(actor.tenant()).triggerStatus(actor,name);return Response.ok(reply.body()).header("Cache-Control","no-store").build();}
}
