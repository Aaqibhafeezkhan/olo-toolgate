// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Optional installation-selected webhook egress; disabled unless exact destination hosts are configured. */
@ApplicationScoped
public class ApplicationIntegrationBootstrap {
    @Inject PostgresStore store; @Inject ContractCodec codec; @Inject TriggerBootstrap triggers;
    @Inject VaultKeyBootstrap keys; @Inject TenantWork tenants; @Inject org.eclipse.microprofile.config.Config config;
    private ScheduledExecutorService executor;
    public ApplicationEvents events(TenantId tenant) {return new ApplicationEvents(store,codec,triggers.forTenant(tenant),Clock.systemUTC());}
    private Set<String> hosts() {
        return Arrays.stream(config.getOptionalValue("toolgate.applications.webhooks.allowed-hosts",String.class).orElse("").split(","))
            .map(String::strip).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public ApplicationWebhooks webhooks(TenantId tenant) {
        return new ApplicationWebhooks(store,codec,events(tenant),new HttpsApplicationWebhookTransport(hosts(),codec,Clock.systemUTC()),keys.required(),Clock.systemUTC());
    }
    void start(@Observes io.quarkus.runtime.StartupEvent event) {
        if(hosts().isEmpty())return;
        var sweep=tenants.bounded(8,tenant->webhooks(tenant).tick(tenant));
        executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"toolgate-application-webhooks");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{try{sweep.tick();}catch(RuntimeException failure){org.jboss.logging.Logger.getLogger(getClass()).warn("Application webhook sweep unavailable");}},1,1,TimeUnit.SECONDS);
    }
    @PreDestroy void stop(){if(executor!=null)executor.shutdownNow();}
}
