// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/** Aliases select provisioning records; only persisted UUIDs cross runtime boundaries. */
@ApplicationScoped
public class TenancyConfiguration {
    @Inject Config config; @Inject TenantRegistry registry;
    private String manifestDefault;
    public void validate() {
        for (String key : java.util.List.of("toolgate.control.endpoint.tenant-id","toolgate.default-tenant-id","toolgate.config.import-overwrite"))
            if(config.getOptionalValue(key,String.class).isPresent())throw new IllegalStateException("Unsupported setting "+key+"; use toolgate.default-tenant-alias and the initialization manifest");
        for(String key:java.util.List.of("TOOLGATE_CONTROL_ENDPOINT_TENANT_ID","TOOLGATE_DEFAULT_TENANT_ID","TOOLGATE_CONFIG_IMPORT_OVERWRITE"))
            if(System.getenv().containsKey(key))throw new IllegalStateException("Unsupported setting "+key);
        if(!java.util.List.of("single","subdomain").contains(mode()))throw new IllegalStateException("Invalid tenancy mode");
        defaultAlias();
    }
    public String mode(){return config.getOptionalValue("toolgate.tenancy.mode",String.class).orElse("single");}
    public void checkManifestTenants(int count){if(mode().equals("single")&&count!=1)throw new IllegalStateException("Multiple tenants require subdomain mode");}
    public void checkSingleRegistry(){if(mode().equals("single")&&registry.hasOtherTenant(defaultId()))throw new IllegalStateException("Single-tenant mode requires a registry containing only its default tenant");}
    void manifestDefault(String alias){manifestDefault=TenantRegistry.alias(alias);defaultAlias();}
    public String defaultAlias(){
        var explicit=config.getOptionalValue("toolgate.default-tenant-alias",String.class);
        if(explicit.isPresent()&&manifestDefault!=null&&!explicit.get().equals(manifestDefault))throw new IllegalStateException("Conflicting default tenant aliases");
        return TenantRegistry.alias(explicit.orElse(manifestDefault!=null?manifestDefault:config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false)?"quickstart":"default"));
    }
    public TenantId defaultId(){var tenant=registry.find(defaultAlias());if(tenant==null)throw Failure.tenantUnavailable();return tenant.id();}
}
