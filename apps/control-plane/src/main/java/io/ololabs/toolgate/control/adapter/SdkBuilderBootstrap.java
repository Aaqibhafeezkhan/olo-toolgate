// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Closed-JSON SDK worker configuration. SDK toolchains are supplied by deployment, never by an authoring request. */
@ApplicationScoped
public class SdkBuilderBootstrap {
    @Inject TenancyConfiguration tenancy;
    @Inject Config config;
    @Inject ContractCodec codec;
    @Inject PostgresStore store;
    private SdkBuilderService service;
    private java.util.concurrent.ScheduledExecutorService loop;
    private String setting(String key) { return config.getValue("toolgate.control.builder." + key, String.class); }
    private Path directory(Path path) throws java.io.IOException {
        if (Files.isSymbolicLink(path)) throw new java.io.IOException("Invalid Builder directory");
        Files.createDirectories(path);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
    private synchronized void load() {
        if (service != null) return;
        String tenant = tenancy.defaultId().value();
        SdkBuildWorker worker = null; BuilderArtifacts artifacts = null;
        if (config.getValue("toolgate.control.builder.enabled", Boolean.class)) {
            try {
                Path data = directory(Path.of(setting("data-directory")).toAbsolutePath().normalize());
                worker = new JsonSdkProcessWorker(Path.of(setting("java-executable")), Path.of(setting("sdk-libraries")),
                        Path.of(setting("cli-executable")), directory(data.resolve("work")), java.time.Duration.ofMinutes(2));
                artifacts = new FileBuilderArtifacts(directory(data.resolve("artifacts")));
            } catch (java.io.IOException invalid) { throw new IllegalStateException("SDK Builder requires a fixed JDK, verified SDK distribution, CLI and private data directory", invalid); }
        }
        service = new SdkBuilderService(store, codec, tenant, java.time.Clock.systemUTC(), worker, artifacts);
        if (worker != null) {
            loop = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "sdk-package-builder"); thread.setDaemon(true); return thread;
            });
            loop.scheduleWithFixedDelay(() -> {
                try { service.workOne(); }
                catch (RuntimeException failure) { org.jboss.logging.Logger.getLogger(SdkBuilderBootstrap.class).warn("SDK Builder worker cycle failed"); }
            }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    void start(@Observes StartupEvent event) { load(); }
    void stop(@Observes ShutdownEvent event) { if (loop != null) loop.shutdownNow(); }
    @Produces @ApplicationScoped SdkBuilderService builder() { load(); return service; }
}
