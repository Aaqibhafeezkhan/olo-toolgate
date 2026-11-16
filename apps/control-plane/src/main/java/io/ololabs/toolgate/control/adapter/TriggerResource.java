// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/**
 * Trigger admin routes (plan 10.2). Every reply carries the resource's ETag and {@code Cache-Control: no-store}. The
 * device's routes under {@code endpoint/triggers} live in {@link EndpointTriggerResource}.
 */
@Path("/api/control/v1") @Produces("application/json") @Blocking @PermitAll
public class TriggerResource {
    @Inject TriggerService service; @Inject RoutingContext routing; @Inject Correlation correlation; @Inject VerifiedActors actors;
    @Inject TriggerRouter router; @Inject TriggerSubscriptions subscriptions; @Inject TriggerStream stream;
    @Inject PostgresStore store; @Inject ContractCodec codec; @Inject TriggerBootstrap bootstrap; @Inject VaultKeyBootstrap vaultKeys;

    private void tls() { if (!routing.request().isSSL()) throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Direct TLS required"); }
    private DirectoryService.Actor actor() { tls(); return actors.management(); }

    @POST @Path("workflow-dispatches:admit") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response admitWorkflow(String document) {
        var actor = actor();
        if (document == null || document.length() > 1024) throw Failure.validation();
        var clock = java.time.Clock.systemUTC();
        var keys = vaultKeys.required();
        var profiles = new WorkflowProfiles(store, codec, new WorkflowProfileDependencies(codec, clock,
            bootstrap.subscriptions(actor.tenant()), bootstrap.forTenant(actor.tenant()), actor.tenant()), keys);
        return response(new WorkflowAdmissions(store, codec, clock, stream, profiles, keys).admit(actor, document, correlation.id()));
    }
    private static Response response(Store.Reply reply) {
        var builder = Response.status(reply.status()).header("Cache-Control", "no-store").entity(reply.body());
        if (reply.revision() > 0) builder.header("ETag", "W/\"" + reply.revision() + "\"");
        return builder.build();
    }
    private static boolean dryRun(String value) {
        if (value == null || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw Failure.validation();
    }

    @GET @Path("triggers/definitions") @io.quarkus.security.Authenticated
    public Response definitions(@QueryParam("deviceId") String device, @QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) { return response(service.definitions(actor(), device, token, size)); }

    @POST @Path("triggers/assignments") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response create(@HeaderParam("Idempotency-Key") String key, @QueryParam("dryRun") String dryRun, String body) {
        return response(service.create(actor(), body, key, dryRun(dryRun), correlation.id()));
    }

    @GET @Path("triggers/assignments") @io.quarkus.security.Authenticated
    public Response list(@QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size, @QueryParam("labelSelector") String selector) {
        return response(service.list(actor(), token, size, selector));
    }

    @GET @Path("triggers/assignments/{name}") @io.quarkus.security.Authenticated
    public Response get(@PathParam("name") String name) { return response(service.get(actor(), name)); }

    @PATCH @Path("triggers/assignments/{name}") @Consumes("application/merge-patch+json") @io.quarkus.security.Authenticated
    public Response patch(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch, @HeaderParam("Idempotency-Key") String key,
                          @QueryParam("dryRun") String dryRun, String body) {
        return response(service.patch(actor(), name, body, ifMatch, key, dryRun(dryRun), correlation.id()));
    }

    @POST @Path("triggers:action") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response bulkAction(String body) {
        return response(service.bulkAction(actor(), body, correlation.id()));
    }

    @POST @Path("triggers/assignments/{name}:action") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response action(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch, String body) {
        return response(service.action(actor(), name, body, ifMatch, correlation.id()));
    }

    @POST @Path("triggers/assignments/{name}:rollback") @io.quarkus.security.Authenticated
    public Response rollback(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch) {
        return response(service.rollback(actor(), name, ifMatch, correlation.id()));
    }

    @DELETE @Path("triggers/assignments/{name}") @io.quarkus.security.Authenticated
    public Response delete(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch, @HeaderParam("Idempotency-Key") String key,
                           @QueryParam("dryRun") String dryRun) {
        return response(service.delete(actor(), name, ifMatch, key, dryRun(dryRun), correlation.id()));
    }

    @GET @Path("triggers/instances") @io.quarkus.security.Authenticated
    public Response instances(@QueryParam("assignment") String assignment, @QueryParam("executorId") String executorId,
                              @QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) {
        return response(service.instances(actor(), assignment, executorId, token, size));
    }

    @GET @Path("triggers/events") @io.quarkus.security.Authenticated
    public Response events(@QueryParam("assignment") String assignment, @QueryParam("instanceId") String instanceId,
                           @QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) {
        return response(service.events(actor(), assignment, instanceId, token, size));
    }

    // ---- module 10: settings and deliveries ----

    @GET @Path("triggers/settings") @io.quarkus.security.Authenticated
    public Response settings() { return response(router.settings(actor())); }

    @PUT @Path("triggers/settings") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response updateSettings(String body) { return response(router.updateSettings(actor(), body, correlation.id())); }

    @GET @Path("triggers/deliveries") @io.quarkus.security.Authenticated
    public Response deliveries(@QueryParam("consumer") String consumer, @QueryParam("state") String state,
                               @QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) {
        return response(router.deliveries(actor(), consumer, state, token, size));
    }

    @POST @Path("triggers/deliveries:redrive") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response redrive(String body) {
        String id;
        try { id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body == null ? "" : body).path("deliveryId").asText(null); }
        catch (Exception invalid) { throw Failure.validation(); }
        return response(router.redrive(actor(), id, correlation.id()));
    }

    // ---- module 12: subscriptions and stream tickets ----

    @POST @Path("triggers/subscriptions") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response createSubscription(@HeaderParam("Idempotency-Key") String key, String body) {
        return response(subscriptions.create(actor(), body, key, correlation.id()));
    }

    @GET @Path("triggers/subscriptions") @io.quarkus.security.Authenticated
    public Response subscriptions(@QueryParam("pageToken") String token, @QueryParam("pageSize") Integer size) { return response(subscriptions.list(actor(), token, size)); }

    @GET @Path("triggers/subscriptions/{name}") @io.quarkus.security.Authenticated
    public Response subscription(@PathParam("name") String name) { return response(subscriptions.get(actor(), name)); }

    @DELETE @Path("triggers/subscriptions/{name}") @io.quarkus.security.Authenticated
    public Response deleteSubscription(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch) {
        return response(subscriptions.delete(actor(), name, ifMatch, correlation.id()));
    }

    /** A single-use socket ticket. The body is a credential: it is never logged (plan 13.2). */
    @POST @Path("triggers/stream/tickets") @io.quarkus.security.Authenticated
    public Response ticket(@HeaderParam("Origin") String origin) { return response(stream.ticket(actor(), origin)); }
}
