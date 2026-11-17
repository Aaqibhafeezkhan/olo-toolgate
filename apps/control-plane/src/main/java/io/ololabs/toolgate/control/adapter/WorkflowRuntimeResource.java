// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.control.application.*;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.time.Clock;

/** Separate workload and Gateway service roles for workflow runtime operations. */
@Path("/internal/v1/runtime") @Produces("application/json") @Blocking
public class WorkflowRuntimeResource {
    static final String ROLE = "toolgate-workflow-runtime";

    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject VerifiedActors actors;
    @Inject Correlation correlation;
    @Inject RoutingContext routing;
    @Inject TriggerBootstrap triggers;
    @Inject VaultKeyBootstrap keys;

    private DirectoryService.Actor worker() {
        if (!routing.request().isSSL()) throw new Failure(ErrorCode.UNAUTHORIZED, 401, "Direct TLS required");
        return actors.service(ROLE);
    }

    private WorkflowLeases leases(DirectoryService.Actor worker) {
        var clock = Clock.systemUTC();
        var vault = keys.required();
        return new WorkflowLeases(store, codec, clock, profiles(worker, clock, vault), vault);
    }

    private WorkflowProfiles profiles(DirectoryService.Actor actor, Clock clock, TenantVaultKeys vault) {
        return new WorkflowProfiles(store, codec, new WorkflowProfileDependencies(codec, clock,
            triggers.subscriptions(actor.tenant()), triggers.forTenant(actor.tenant()), actor.tenant()), vault);
    }

    @POST @Path("claim") @Consumes("application/json") @RolesAllowed(ROLE)
    public Response claim(String document) {
        var worker = worker();
        if (document == null || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) throw Failure.validation();
        var request = codec.model(document, io.ololabs.toolgate.contracts.v2.WorkflowWorkerCapabilities.class);
        return reply(leases(worker).claim(worker, request.instanceId(), request.compilerDigest(), request.graphDigests(), correlation.id()));
    }

    @POST @Path("workers:heartbeat") @Consumes("application/json") @RolesAllowed(ROLE)
    public Response heartbeat(String document) {
        var worker = worker();
        if (document == null || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) throw Failure.validation();
        var request = codec.model(document, io.ololabs.toolgate.contracts.v2.WorkflowWorkerCapabilities.class);
        return reply(leases(worker).heartbeat(worker, request.instanceId(), request.compilerDigest(), request.graphDigests(), correlation.id()));
    }

    @GET @Path("graph-candidates/{profileId}/{digest}/{workflowId}") @RolesAllowed(ROLE)
    public Response graphCandidate(@PathParam("profileId") String profileId, @PathParam("digest") String digest,
            @PathParam("workflowId") String workflowId, @QueryParam("expectedGeneration") Long expectedGeneration) {
        var worker = worker();
        if (expectedGeneration == null) throw Failure.validation();
        return reply(profiles(worker, Clock.systemUTC(), keys.required()).graphCandidate(worker,
            profileId, digest, workflowId, expectedGeneration));
    }

    @POST @Path("runs/{runId}/finish") @Consumes("application/json") @RolesAllowed(ROLE)
    public Response finish(@PathParam("runId") String runId, String document) {
        var worker = worker();
        if (document == null || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 49152) throw Failure.validation();
        if (!(codec.value(document) instanceof JsonNode value) || !value.isObject() || value.size() != 5
                || !value.path("leaseId").isTextual() || !value.path("leaseToken").isTextual() || !value.path("state").isTextual()
                || !value.has("result") || !value.has("code") || !(value.get("code").isNull() || value.get("code").isTextual()))
            throw Failure.validation();
        return reply(leases(worker).finish(worker, runId, value.path("leaseId").textValue(), value.path("leaseToken").textValue(),
            value.path("state").textValue(), value.get("result"), value.get("code").textValue(), correlation.id()));
    }

    @POST @Path("runs/{runId}/steps:authorize") @Consumes("application/json") @RolesAllowed(ROLE)
    public Response authorizeStep(@PathParam("runId") String runId, String document) {
        var worker = worker();
        if (document == null || document.length() > 2048) throw Failure.validation();
        if (!(codec.value(document) instanceof JsonNode value) || !value.isObject() || value.size() != 5
                || !value.path("leaseId").isTextual() || !value.path("leaseToken").isTextual()
                || !value.path("subjectKind").isTextual() || !value.path("subjectId").isTextual() || !value.path("modelGroupId").isTextual())
            throw Failure.validation();
        return reply(leases(worker).authorizeStep(worker, runId, value.path("leaseId").textValue(), value.path("leaseToken").textValue(),
            value.path("subjectKind").textValue(), value.path("subjectId").textValue(), value.path("modelGroupId").textValue()));
    }

    @POST @Path("model:resolve") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway")
    public Response resolveModel(String document) {
        var gateway = actors.service("toolgate-relay-gateway");
        if (document == null || document.length() > 1024 || !(codec.value(document) instanceof JsonNode value)
                || !value.isObject() || value.size() != 2 || !value.path("runId").isTextual() || !value.path("stepToken").isTextual())
            throw Failure.validation();
        String runId = value.path("runId").textValue();
        if (!runId.matches("run-[0-9a-f-]{36}")) throw Failure.validation();
        var clock = Clock.systemUTC();
        var vault = keys.required();
        var selection = new WorkflowModelSelection(profiles(gateway, clock, vault), codec, clock);
        return reply(store.transaction(gateway.tenant(), false, tx -> {
            var model = selection.resolve(tx, gateway.tenant(), runId, value.path("stepToken").textValue());
            var credential = new VaultService(store, codec, vault).workflowModelCredential(gateway.tenant(), tx, model);
            return new Store.Reply(200, codec.json(java.util.Map.of("origin", model.origin(), "modelId", model.modelId(),
                "credential", credential, "generation", model.generation())), 0);
        }));
    }

    @POST @Path("tool:resolve") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway")
    public Response resolveTool(String document) {
        var gateway = actors.service("toolgate-relay-gateway");
        if (document == null || document.length() > 1024 || !(codec.value(document) instanceof JsonNode value)
                || !value.isObject() || value.size() != 3 || !value.path("runId").isTextual() || !value.path("stepToken").isTextual()
                || !value.has("toolId") || !(value.get("toolId").isNull() || value.get("toolId").isTextual())) throw Failure.validation();
        String runId = value.path("runId").textValue();
        String toolId = value.path("toolId").textValue();
        if (!runId.matches("run-[0-9a-f-]{36}") || toolId != null && !toolId.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}"))
            throw Failure.validation();
        var clock = Clock.systemUTC();
        var authority = new WorkflowToolAuthority(codec, new WorkflowModelSelection(profiles(gateway, clock, keys.required()), codec, clock), clock);
        return reply(store.transaction(gateway.tenant(), false, tx -> new Store.Reply(200, codec.json(authority.resolve(tx,
            gateway.tenant(), runId, value.path("stepToken").textValue(), toolId, correlation.id())), 0)));
    }

    private static Response reply(Store.Reply value) {
        return Response.status(value.status()).header("Cache-Control", "no-store").entity(value.body()).build();
    }
}
