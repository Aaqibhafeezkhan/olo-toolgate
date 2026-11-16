// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.time.Clock;

/** Tenant-scoped operator access to private workflow results, with current Super Admin authority. */
@Path("/api/control/v1/workflow-runs") @Produces("application/json") @Blocking @io.quarkus.security.Authenticated
public class WorkflowRunResource {
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject VerifiedActors actors;
    @Inject TriggerBootstrap triggers;
    @Inject VaultKeyBootstrap keys;

    @GET @Path("{runId}")
    public Response get(@PathParam("runId") String runId) {
        var actor = actors.management();
        var clock = Clock.systemUTC();
        var vault = keys.required();
        var profiles = new WorkflowProfiles(store, codec, new WorkflowProfileDependencies(codec, clock,
            triggers.subscriptions(actor.tenant()), triggers.forTenant(actor.tenant()), actor.tenant()), vault);
        var reply = new WorkflowLeases(store, codec, clock, profiles, vault).result(actor, runId);
        return Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body()).build();
    }
}
