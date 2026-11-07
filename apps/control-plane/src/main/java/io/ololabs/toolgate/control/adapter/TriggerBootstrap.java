// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Triggers control core (issue #274, module 4), off by default behind {@code toolgate.triggers.control-core.enabled}
 * until the client slice passes (plan 24.1). It needs the endpoint and fleet services: devices authenticate with their
 * certificates, and the desired trigger document is signed by the fleet's organization key.
 */
@ApplicationScoped
public class TriggerBootstrap {
    public static final String FLAG = "toolgate.triggers.control-core.enabled";
    @Inject TenantRequest request; @Inject Config config; @Inject ContractCodec codec; @Inject PostgresStore store;
    @Inject EndpointBootstrap endpoints; @Inject FleetBootstrap fleet;

    private static volatile Boolean profileDeclares;

    /**
     * On with the module flag, or when the deployment profile this Control applies declares a trigger assignment:
     * uploading such a profile enables triggers wherever it is applied (ADR 058, module 5).
     */
    static boolean enabled(Config config) {
        if (config.getOptionalValue(FLAG, Boolean.class).orElse(false)) return true;
        if (profileDeclares == null) {
            var path = config.getOptionalValue("toolgate.control.profile.path", String.class);
            boolean declares = false;
            if (path.isPresent()) {
                try {
                    var profile = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ProtectedCustody.read(java.nio.file.Path.of(path.get()), 262144));
                    declares = PackageProvisioning.declaresTriggers(profile);
                } catch (Exception unreadable) { declares = false; }
            }
            profileDeclares = declares;
        }
        return profileDeclares;
    }

    public TriggerService forTenant(io.ololabs.toolgate.control.domain.Ids.TenantId tenant) {
        boolean on = enabled(config) && endpoints.profile(tenant) != null;
        return new TriggerService(store, codec, on ? fleet.triggerCrypto() : null, endpoints.forTenant(tenant), java.time.Clock.systemUTC(), on, tenant.value());
    }

    /** Check-in ingest for a tenant (module 5); off with the module flag, when a check-in's triggers block is refused. */
    public TriggerIngest ingest(io.ololabs.toolgate.control.domain.Ids.TenantId tenant) { return new TriggerIngest(forTenant(tenant), codec); }

    @Produces @jakarta.enterprise.context.RequestScoped TriggerService triggers() { return forTenant(request.tenant()); }
}
