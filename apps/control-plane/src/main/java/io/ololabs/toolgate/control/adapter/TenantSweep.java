// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** One bounded page per tick; a tenant failure cannot restart enumeration at the first tenant. */
final class TenantSweep {
    record Result(int attempted,int failed,boolean cycleComplete) {}
    private final int limit;
    private final BiFunction<String,Integer,List<TenantId>> enumerate;
    private final Consumer<TenantId> work;
    private String cursor="";
    TenantSweep(int limit,BiFunction<String,Integer,List<TenantId>> enumerate,Consumer<TenantId> work){
        if(limit<1||limit>256)throw new IllegalArgumentException("Invalid tenant sweep page size");
        this.limit=limit;this.enumerate=enumerate;this.work=work;
    }
    Result tick(){
        // Registry failures leave the cursor unchanged for the next scheduled retry.
        var page=enumerate.apply(cursor,limit);int failed=0;
        if(page.size()>limit)throw new IllegalStateException("Tenant sweep page exceeds its bound");
        for(var tenant:page){
            cursor=tenant.value();
            try{work.accept(tenant);}catch(RuntimeException unavailable){
                failed++;
                org.jboss.logging.Logger.getLogger(TenantSweep.class).warnf("tenant_background_failed tenant_id=%s",tenant.value());
            }
        }
        boolean complete=page.size()<limit;
        if(complete)cursor="";
        return new Result(page.size(),failed,complete);
    }
}
