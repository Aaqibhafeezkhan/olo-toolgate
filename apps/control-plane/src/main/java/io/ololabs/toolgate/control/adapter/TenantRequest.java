// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/** The router binds once. No default fallback or thread-local state is available to HTTP services. */
@RequestScoped
public class TenantRequest {
    static final String KEY=TenantRequest.class.getName();
    @Inject RoutingContext routing;
    public TenantRequestResolver.Context context(){return require(routing);}
    public TenantId tenant(){return context().tenant();}
    static TenantRequestResolver.Context require(RoutingContext routing){
        TenantRequestResolver.Context value=routing.get(KEY);if(value==null)throw Failure.tenantUnavailable();return value;
    }
}
