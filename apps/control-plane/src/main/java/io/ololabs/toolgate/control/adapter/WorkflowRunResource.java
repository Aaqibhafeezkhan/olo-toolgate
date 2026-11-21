// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.time.Clock;

/** Tenant-scoped caller access to private workflow progress and results. */
@Path("/api/control/v1/workflow-runs") @Produces("application/json") @Blocking @io.quarkus.security.Authenticated
public class WorkflowRunResource {
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject VerifiedActors actors;
    @Inject TriggerBootstrap triggers;
    @Inject VaultKeyBootstrap keys;

    @GET @Path("{runId}/progress")
    public Response progress(@PathParam("runId") String run,@QueryParam("after") @DefaultValue("0") long after) {
        var value=new WorkflowProgress(store,codec,Clock.systemUTC()).read(actors.human(),run,after);
        return Response.status(value.status()).header("Cache-Control","no-store").entity(value.body()).build();
    }
    @GET @Path("{runId}")
    public Response get(@PathParam("runId") String runId) {
        var actor = actors.human();
        var clock = Clock.systemUTC();
        var vault = keys.required();
        var profiles = new WorkflowProfiles(store, codec, new WorkflowProfileDependencies(codec, clock,
            triggers.subscriptions(actor.tenant()), triggers.forTenant(actor.tenant()), actor.tenant()), vault);
        var reply = new WorkflowLeases(store, codec, clock, profiles, vault).result(actor, runId);
        return Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body()).build();
    }
}
