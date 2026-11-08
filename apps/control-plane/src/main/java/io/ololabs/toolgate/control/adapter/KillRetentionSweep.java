// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.*;
import org.jboss.logging.Logger;

/** Background archive maintenance; acknowledgements and operator reads also apply the same rule. */
@ApplicationScoped
public class KillRetentionSweep {
    private static final Logger LOG=Logger.getLogger(KillRetentionSweep.class);
    @Inject TenantRegistry tenants;@Inject PostgresStore store;@Inject ContractCodec codec;
    @Inject EnterpriseOperations operations;@Inject FleetBootstrap fleet;
    private ScheduledExecutorService executor;
    void start(@Observes StartupEvent ignored){
        executor=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"toolgate-kill-retention");thread.setDaemon(true);return thread;});
        executor.scheduleWithFixedDelay(this::sweep,60,3600,TimeUnit.SECONDS);
    }
    void sweep(){
        String cursor="";
        try{
            while(!Thread.currentThread().isInterrupted()){
                var page=tenants.activeAfter(cursor,64);if(page.isEmpty())return;
                for(var tenant:page){
                    cursor=tenant.value();
                    try{new KillSwitch(store,codec,operations,()->fleet.killCrypto()).sweep(tenant);}
                    catch(RuntimeException unavailable){LOG.warnf("kill_retention_unavailable tenant=%s",tenant.value());}
                }
                if(page.size()<64)return;
            }
        }catch(RuntimeException unavailable){LOG.warn("kill_retention_registry_unavailable");}
    }
    @PreDestroy void stop(){if(executor!=null)executor.shutdownNow();}
}
