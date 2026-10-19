// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Applies the mounted deployment profile (TOOLGATE_CONTROL_PROFILE_PATH) after the installation, its configuration file
 *  and the core inventory check. An invalid profile or lock stops startup before any package changes. A profile waiting
 *  for a Tool Host, or one whose required package failed, keeps Control running for repair and is reapplied — an
 *  idempotent no-op once nothing changes — until it is READY. A profile that names device groups is reapplied every
 *  minute even when READY, so devices that join a group later receive its client releases. The profile selects; it never
 *  mints an identity or grant. */
@ApplicationScoped
public class PackageProvisioningBootstrap {
    @Inject TenancyConfiguration tenancy;
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(PackageProvisioningBootstrap.class);
    @Inject Config config; @Inject PostgresStore store; @Inject ContractCodec codec; @Inject ServerPackages packages; @Inject Marketplace marketplace;
    @Inject ClientReleases clients; @Inject FleetService fleet;
    private ScheduledExecutorService executor;

    PackageProvisioning service() throws java.io.IOException {
        var lock = config.getOptionalValue("toolgate.control.core-packages.lock-path", String.class);
        var inventory = lock.isEmpty() ? java.util.List.<CorePackageInventory.Artifact>of() : CorePackageInventory.load(Path.of(lock.get()));
        PackageProvisioning.CoreArtifacts core = (id, version) -> inventory.stream().filter(a -> a.id().equals(id) && a.version().equals(version)).findFirst()
            .map(a -> new PackageProvisioning.Core(a.packageDigest(), () -> { try { return a.bytes(); } catch (java.io.IOException changed) { throw Failure.unavailable(); } })).orElse(null);
        return new PackageProvisioning(store, codec, packages, marketplace, core, clients, fleet, java.time.Clock.systemUTC());
    }

    /** Reads recorded status only; it neither verifies artifacts nor contacts the Marketplace. */
    PackageProvisioning reader() { return new PackageProvisioning(store, codec, packages, null, null, null, null, java.time.Clock.systemUTC()); }

    /** The installation's Super Admin, resolved through the central role resolver, as for the core inventory import. */
    private DirectoryService.Actor deployer(Ids.TenantId tenant) {
        return store.transaction(tenant, false, tx -> {
            var directory = tx.load(); var access = new ManagementAccess(codec);
            String user = directory.entries().values().stream().filter(e -> e.id().kind() == Ids.Kind.USER && e.enabled() && access.superAdministrator(directory, e.id().value()))
                .map(e -> e.id().value()).sorted().findFirst().orElseThrow(ManagementAccess::denied);
            return new DirectoryService.Actor(tenant, DirectoryService.digest("deployment-profile\n" + user), true, true, user);
        });
    }

    void start(@Observes @jakarta.annotation.Priority(13) StartupEvent event) {
        var path = config.getOptionalValue("toolgate.control.profile.path", String.class);
        if (path.isEmpty()) return;
        String profile; PackageProvisioning service; Ids.TenantId tenant;
        try {
            profile = new String(ProtectedCustody.read(Path.of(path.get()), 262144), java.nio.charset.StandardCharsets.UTF_8);
            service = service(); tenant = tenancy.defaultId();
        } catch (Exception invalid) { throw new IllegalStateException("Deployment profile rejected; verify the profile file and the sealed core package lock", invalid); }
        String state = apply(service, tenant, profile, true);
        boolean follows = service.followsDevices(profile);
        if (state.equals("READY") && !follows) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "toolgate-profile-provisioning"); t.setDaemon(true); return t; });
        schedule(service, tenant, profile, state, follows);
    }

    private void schedule(PackageProvisioning service, Ids.TenantId tenant, String profile, String state, boolean follows) {
        executor.schedule(() -> { String next = apply(service, tenant, profile, false); if (follows || !next.equals("READY")) schedule(service, tenant, profile, next, follows); },
            state.equals("PENDING") ? 15 : 60, TimeUnit.SECONDS);
    }

    private String apply(PackageProvisioning service, Ids.TenantId tenant, String profile, boolean startup) {
        try {
            var reply = service.apply(deployer(tenant), profile);
            var status = (com.fasterxml.jackson.databind.JsonNode) codec.value(reply.body());
            String state = status.path("state").asText();
            LOG.infof("profile_provisioning profile=%s generation=%d state=%s", status.path("profileId").asText(), status.path("generation").asLong(), state);
            return state;
        } catch (Failure invalid) {
            if (startup && invalid.status() == 400) throw new IllegalStateException("Deployment profile rejected: " + invalid.getMessage(), invalid);
            LOG.warnf("profile_provisioning state=FAILED status=%d", invalid.status());
            return "FAILED";
        } catch (RuntimeException unavailable) {
            if (startup) LOG.warnf("profile_provisioning deferred: %s", unavailable.getClass().getSimpleName());
            return "FAILED";
        }
    }

    @PreDestroy void stop() { if (executor != null) executor.shutdownNow(); }
}
