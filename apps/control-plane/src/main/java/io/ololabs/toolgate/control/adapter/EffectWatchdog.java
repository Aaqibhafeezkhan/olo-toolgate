// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.EnterpriseOperations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;
import io.quarkus.runtime.StartupEvent;
import java.util.concurrent.*;

/** Bounded restart-safe sweeps. A failed sweep cannot authorize or redispatch an effect. */
@ApplicationScoped
public class EffectWatchdog {
    @Inject PostgresStore store;@Inject EnterpriseOperations operations;@Inject ContractCodec codec;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;
    private ScheduledExecutorService executor;
    private volatile long unknown,outbox;

    void start(@Observes StartupEvent event){executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"toolgate-effect-watchdog");t.setDaemon(true);return t;});metrics.gauge("toolgate_control_unknown_outcomes",this,v->v.unknown);metrics.gauge("toolgate_control_unpublished_snapshot_events",this,v->v.outbox);executor.scheduleWithFixedDelay(this::sweep,5,5,TimeUnit.SECONDS);}
    private void sweep(){try{long unknownCount=0,outboxCount=0;for(var tenant:store.tenants()){int count=operations.sweep(tenant);new io.ololabs.toolgate.control.application.RunEvents(store,codec,operations).purge(tenant,System.currentTimeMillis());if(count>0)metrics.counter("toolgate_control_expired_effects_total").increment(count);var status=store.transaction(tenant,false,tx->tx.enterprise().operationalStatus(System.currentTimeMillis()));unknownCount+=status.unknownOutcomes();outboxCount+=status.pendingSnapshotEvents();}unknown=unknownCount;outbox=outboxCount;metrics.counter("toolgate_control_watchdog_sweeps_total","result","success").increment();}catch(RuntimeException unavailable){metrics.counter("toolgate_control_watchdog_sweeps_total","result","failed").increment();}}
    @PreDestroy void stop(){if(executor!=null)executor.shutdownNow();}
}
