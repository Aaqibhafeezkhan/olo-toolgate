// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.WorkflowProfileReviewRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.time.Clock;

/** Authenticated profile staging/review and explicit activation; no runnable half-profile is exposed. */
@Path("/api/control/v1/workflow-profiles")
@io.quarkus.security.Authenticated
@io.smallrye.common.annotation.Blocking
@Produces("application/json")
public class WorkflowProfileResource {
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject VerifiedActors actors;
    @Inject Correlation correlation;
    @Inject TriggerBootstrap triggers;
    @Inject PackageProvisioningBootstrap provisioning;
    @Inject VaultKeyBootstrap keys;
    @Inject WorkflowGraphBootstrap graphs;
    private WorkflowProfiles service(DirectoryService.Actor actor) {
        return new WorkflowProfiles(store, codec, new WorkflowProfileDependencies(codec, Clock.systemUTC(),
            triggers.subscriptions(actor.tenant()), triggers.forTenant(actor.tenant()), actor.tenant()), keys.required());
    }
    private Response reply(Store.Reply reply) {
        return Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body()).build();
    }
    private String body(String document) {
        if (document == null || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144) throw Failure.validation();
        return document;
    }
    @POST @Path("{profileId}/workflows/{workflowId}/runs") @Consumes("application/json")
    public Response startRun(@PathParam("profileId") String profile,@PathParam("workflowId") String workflow,
            @HeaderParam("Idempotency-Key") String key,String document) {
        var actor=actors.human();
        return reply(new WorkflowManualRuns(store,codec,Clock.systemUTC(),service(actor),keys.required())
            .start(actor,profile,workflow,body(document),key,correlation.id()));
    }
    @POST @Consumes("application/json")
    public Response upload(String document) {
        var actor = actors.management(); return reply(service(actor).upload(actor, body(document), correlation.id()));
    }
    @GET
    public Response list(@QueryParam("afterProfileId") String afterProfileId, @QueryParam("afterDigest") String afterDigest,
                         @QueryParam("limit") @DefaultValue("50") int limit) {
        var actor = actors.management(); return reply(service(actor).list(actor, afterProfileId, afterDigest, limit));
    }
    @GET @Path("{profileId}/revisions/{digest}")
    public Response get(@PathParam("profileId") String id, @PathParam("digest") String digest) {
        var actor = actors.management(); return reply(service(actor).get(actor, id, digest));
    }
    @GET @Path("{profileId}/revisions/{digest}/diagnostics")
    public Response diagnostics(@PathParam("profileId") String id, @PathParam("digest") String digest) {
        var actor = actors.management(); return reply(service(actor).diagnostics(actor, id, digest));
    }
    @GET @Path("published")
    public Response published(@QueryParam("afterProfileId") String after, @QueryParam("limit") @DefaultValue("50") int limit) {
        var actor = actors.human(); return reply(service(actor).listPublished(actor, after, limit));
    }
    @GET @Path("published/{profileId}/{digest}")
    public Response publishedConfiguration(@PathParam("profileId") String id, @PathParam("digest") String digest) {
        var actor = actors.human(); return reply(service(actor).publishedConfiguration(actor, id, digest));
    }
    @GET @Path("{profileId}/revisions/{digest}/document")
    public Response document(@PathParam("profileId") String id, @PathParam("digest") String digest) {
        var actor = actors.management(); return reply(service(actor).document(actor, id, digest, false));
    }
    @POST @Path("{profileId}/review") @Consumes("application/json")
    public Response review(@PathParam("profileId") String id, String document) {
        var actor = actors.management(); return reply(service(actor).review(actor, id, body(document), correlation.id()));
    }
    @POST @Path("{profileId}/provision") @Consumes("application/json")
    public Response provision(@PathParam("profileId") String id, String document) {
        var actor = actors.management(); var service = service(actor);
        var request = codec.model(body(document), WorkflowProfileReviewRequest.class);
        var desired = service.document(actor, id, request.profileDigest(), true);
        try { return reply(provisioning.service().apply(actor, desired.body())); }
        catch (java.io.IOException unavailable) { throw Failure.unavailable(); }
    }
    @POST @Path("{profileId}/publish") @Consumes("application/json")
    public Response publish(@PathParam("profileId") String id, String document) {
        var actor = actors.management(); return reply(service(actor).publish(actor, id, body(document), correlation.id()));
    }
    @POST @Path("{profileId}/graphs:prepare") @Consumes("application/json")
    public Response prepareGraphs(@PathParam("profileId") String id, String document) {
        var actor = actors.management();
        // Resolve the builder only after current management authorization has succeeded.
        var service = service(actor);
        var request = codec.model(body(document), io.ololabs.toolgate.contracts.v2.WorkflowProfilePublishRequest.class);
        service.document(actor, id, request.profileDigest(), true);
        return reply(new WorkflowGraphPreparation(codec, service, graphs.builder())
            .prepare(actor, id, document, correlation.id()));
    }
}
