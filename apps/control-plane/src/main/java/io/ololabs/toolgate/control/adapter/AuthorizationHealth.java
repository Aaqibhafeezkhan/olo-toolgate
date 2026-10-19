// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;
import io.ololabs.toolgate.control.domain.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.*;
import org.eclipse.microprofile.config.Config;

/** Effect readiness requires a validated independent key, current database and canonical graph. */
@Readiness @ApplicationScoped
public class AuthorizationHealth implements HealthCheck {
    @Inject TenancyConfiguration tenancy;
    @Inject EffectBootstrap effects;@Inject PostgresStore store;@Inject ContractCodec codec;@Inject Config config;
    public HealthCheckResponse call(){
        var response=HealthCheckResponse.named("current-group-authority");
        try{if(!effects.ready())return response.down().build();
            var tenant=tenancy.defaultId();
            long revision=store.transaction(tenant,false,tx->{var graph=tx.load();codec.validatePolicies(graph);tx.enterprise().authorizationEpoch();return graph.revision();});
            return response.up().withData("directoryRevision",revision).withData("permitLifetimeMs",10000).withData("gatewayFreshnessMs",5000).build();
        }catch(RuntimeException unavailable){return response.down().build();}
    }
}
