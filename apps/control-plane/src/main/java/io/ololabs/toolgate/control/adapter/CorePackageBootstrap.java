// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;

/** Deployment-time import only. Package deployment and runtime grants remain separate authorities. */
@ApplicationScoped
public class CorePackageBootstrap {
    @Inject Config config; @Inject PostgresStore store; @Inject ContractCodec codec; @Inject ServerPackages packages;
    void start(@Observes @jakarta.annotation.Priority(12) StartupEvent event) {
        var path = config.getOptionalValue("toolgate.control.core-packages.lock-path", String.class);
        if (path.isEmpty()) return;
        try {
            var inventory = CorePackageInventory.load(Path.of(path.get()));
            var tenant = new Ids.TenantId(config.getValue("toolgate.control.endpoint.tenant-id", String.class));
            var actor = store.transaction(tenant, false, tx -> {
                var directory = tx.load(); var access = new ManagementAccess(codec);
                String user = directory.entries().values().stream().filter(e -> e.id().kind() == Ids.Kind.USER && e.enabled()
                        && access.superAdministrator(directory, e.id().value())).map(e -> e.id().value()).sorted().findFirst().orElseThrow(ManagementAccess::denied);
                return new DirectoryService.Actor(tenant, DirectoryService.digest("core-packages\n" + user), true, true, user);
            });
            for (var artifact : inventory) packages.upload(actor, artifact.bytes(), "core-packages-" + artifact.packageDigest().substring(7, 39));
        } catch (Exception invalid) { throw new IllegalStateException("Core package deployment rejected; verify the sealed inventory, artifacts and setup identity", invalid); }
    }
}
