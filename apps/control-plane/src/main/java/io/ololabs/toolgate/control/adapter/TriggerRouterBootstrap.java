// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Router workers (plan 12; triggers module 10). One dedicated, bounded thread, so routing never competes with tool-call
 * admission (bulkhead). Each tick visits a page of tenants in round-robin order (a noisy tenant cannot starve the next)
 * and gives each a bounded slice: up to 5 outbox batches, one dispatch batch and, once a minute, one purge batch.
 * Every slice is its own tenant transaction, so several Control replicas can run it: on PostgreSQL the row revision
 * guard makes concurrent routing of the same outbox row fail for all but one.
 */
@ApplicationScoped
public class TriggerRouterBootstrap {
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(TriggerRouterBootstrap.class);
    @Inject TriggerBootstrap triggers; @Inject TenantWork tenants;
    private ScheduledExecutorService executor;
    private volatile long lastPurge;

    void start(@Observes @jakarta.annotation.Priority(20) StartupEvent event) {
        if (!triggers.enabled()) return;
        var sweep = tenants.bounded(32, tenant -> {
            var router = triggers.router(tenant);
            for (int batch = 0; batch < 5 && router.route() > 0; batch++) { }
            router.dispatch();
            if (System.currentTimeMillis() - lastPurge >= 60_000) router.purge();
        });
        executor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "toolgate-trigger-router"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(() -> {
            try {
                long started = System.currentTimeMillis();
                var result = sweep.tick();
                if (result.cycleComplete() && started - lastPurge >= 60_000) lastPurge = started;
            } catch (RuntimeException unavailable) { LOG.warn("trigger_router enumeration unavailable"); }
        }, 2, 1, TimeUnit.SECONDS);
    }

    void stop(@Observes ShutdownEvent event) { if (executor != null) executor.shutdownNow(); }
}
