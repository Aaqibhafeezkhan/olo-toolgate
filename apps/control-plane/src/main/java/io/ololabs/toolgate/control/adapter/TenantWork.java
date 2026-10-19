// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Consumer;

/** Explicit platform enumeration; a tenant failure must not skip later tenants in the page. */
@ApplicationScoped
public class TenantWork {
    @Inject TenantRegistry registry;
    public void active(Consumer<TenantId> action){
        String cursor="";
        while(true){var page=registry.activeAfter(cursor,128);for(var tenant:page){run(tenant,action);cursor=tenant.value();}if(page.size()<128)return;}
    }
    static void run(TenantId tenant,Consumer<TenantId> action){
        try{action.accept(tenant);}catch(RuntimeException failure){org.jboss.logging.Logger.getLogger(TenantWork.class).warnf("tenant_background_failed tenant_id=%s",tenant.value());}
    }
}
