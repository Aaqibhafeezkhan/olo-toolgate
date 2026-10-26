// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.InsightsService;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.TenantAnalytics;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Path("/api/control/v1/analytics") @Produces("application/json") @Authenticated @Blocking
public class TenantAnalyticsResource {
    @Inject VerifiedActors actors;
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject InsightsService insights;
    @GET public Response overview(@QueryParam("from") Long from, @QueryParam("to") Long to,
                                  @QueryParam("tenant") String tenant, @QueryParam("tenantId") String tenantId) {
        var actor = actors.management();
        // Reject tenant-selection attempts rather than silently accepting a misleading filter.
        if (tenant != null || tenantId != null || from == null || to == null) throw Failure.validation();
        var result = new TenantAnalytics(store, codec, insights).overview(actor, store.insightsMode(), from, to, System.currentTimeMillis());
        return Response.ok(codec.json(result)).header("Cache-Control", "no-store").build();
    }
}
