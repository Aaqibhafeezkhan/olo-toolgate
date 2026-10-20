// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.config.Config;
import java.nio.file.*;

/** Embedded builds are restricted to the local, single-tenant Quickstart composition. */
@ApplicationScoped
public class SdkBuilderBootstrap {
    @Inject TenantRequest request;
    @Inject TenancyConfiguration tenancy;
    @Inject Config config;
    @Inject ContractCodec codec;
    @Inject PostgresStore store;
    private java.util.concurrent.ScheduledExecutorService loop;
    private Path data;
    private TenantId owner;
    private String setting(String key){return config.getValue("toolgate.control.builder."+key,String.class);}
    private Path directory(Path path)throws java.io.IOException {
        if(Files.isSymbolicLink(path))throw new java.io.IOException("Invalid Builder directory");
        Files.createDirectories(path);
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
    private SdkBuildWorker worker(Path root)throws java.io.IOException {
        return new JsonSdkProcessWorker(Path.of(setting("java-executable")),Path.of(setting("sdk-libraries")),Path.of(setting("cli-executable")),root,java.time.Duration.ofMinutes(2));
    }
    public SdkBuilderService forTenant(TenantId tenant){
        if(data!=null&&!tenant.equals(owner))throw Failure.unavailable();
        SdkBuildWorker worker=null;BuilderArtifacts artifacts=null;
        if(data!=null)try {
            Path own=directory(data.resolve(tenant.value()));
            worker=worker(directory(own.resolve("work")));
            artifacts=new FileBuilderArtifacts(directory(own.resolve("artifacts")));
        }catch(java.io.IOException invalid){throw Failure.unavailable();}
        return new SdkBuilderService(store,codec,tenant.value(),java.time.Clock.systemUTC(),worker,artifacts);
    }
    void start(@Observes StartupEvent event){
        if(!config.getValue("toolgate.control.builder.enabled",Boolean.class))return;
        // Paths and environment filtering cannot contain hostile build code running as Control.
        if(!tenancy.mode().equals("single")||!config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false))
            throw new IllegalStateException("Embedded SDK builds require single-tenant Quickstart; use isolated external builds for shared or production Control");
        owner=tenancy.defaultId();
        try{data=directory(Path.of(setting("data-directory")).toAbsolutePath().normalize());worker(data);}
        catch(java.io.IOException invalid){throw new IllegalStateException("SDK Builder requires a fixed JDK, SDK distribution, CLI and private data directory",invalid);}
        loop=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"sdk-package-builder");thread.setDaemon(true);return thread;});
        loop.scheduleWithFixedDelay(()->{
            try {
                TenantWork.run(owner,id->forTenant(id).workOne());
            }catch(RuntimeException failure){org.jboss.logging.Logger.getLogger(SdkBuilderBootstrap.class).warn("SDK Builder enumeration unavailable");}
        },1,1,java.util.concurrent.TimeUnit.SECONDS);
    }
    void stop(@Observes ShutdownEvent event){if(loop!=null)loop.shutdownNow();}
    @Produces @jakarta.enterprise.context.RequestScoped SdkBuilderService builder(){return forTenant(request.tenant());}
}
