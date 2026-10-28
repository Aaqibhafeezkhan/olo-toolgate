// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.*;

/** Deployment readiness is independent of any individual tenant. */
@Readiness @ApplicationScoped
public class AuthorizationHealth implements HealthCheck {
    @Inject EffectBootstrap effects;
    @Inject TenantRegistry registry;
    public HealthCheckResponse call(){
        return HealthCheckResponse.named("current-group-authority").status(effects.ready()&&registry.ready())
            .withData("permitLifetimeMs",10000).withData("gatewayFreshnessMs",5000).build();
    }
}
