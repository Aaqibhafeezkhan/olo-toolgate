// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.DirectoryService;
import io.ololabs.toolgate.control.application.ManagementAccess;
import io.ololabs.toolgate.control.domain.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** A fresh installation's configuration file, applied once right after the reviewed installation packet. The operator
 *  chose the file, so it is trusted like the built-in presets: a MERGE import as the first Super Admin, without reviews. */
@ApplicationScoped
public class InitializeBootstrap {
    @Inject Config config;@Inject PostgresStore store;@Inject ContractCodec codec;@Inject DirectoryService directory;
    void start(@Observes @jakarta.annotation.Priority(11) StartupEvent event){
        var path=config.getOptionalValue("toolgate.control.initialize.path",String.class);if(path.isEmpty()||!java.nio.file.Files.isRegularFile(Path.of(path.get())))return;
        try{
            var raw=new String(ProtectedCustody.read(Path.of(path.get()),2097152),java.nio.charset.StandardCharsets.UTF_8);
            var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)codec.value(raw);var tenant=new Ids.TenantId(snapshot.path("tenantId").asText());
            String marker=DirectoryService.digest("initialize\n"+raw);
            var state=store.transaction(tenant,false,tx->tx.enterprise().recoveryApplied(marker)?null:tx.load());if(state==null)return;
            var access=new ManagementAccess(codec);
            String admin=state.entries().values().stream().filter(e->e.id().kind()==Ids.Kind.USER&&e.enabled()&&access.superAdministrator(state,e.id().value())).map(e->e.id().value()).sorted().findFirst().orElseThrow(ManagementAccess::denied);
            snapshot.put("revision",state.revision());
            var envelope=codec.json(java.util.Map.of("snapshot",snapshot,"mode","MERGE","dryRun",false));
            var actor=new DirectoryService.Actor(tenant,DirectoryService.digest("initialize\n"+admin),true,true,admin);
            directory.importConfig(actor,envelope,false,state.revision(),random(),random());
            store.transaction(tenant,true,tx->{tx.enterprise().rememberRecovery(marker);tx.audit(actor.id(),"INITIALIZE_APPLY","configuration-file",tx.load().revision(),"initialize-"+marker.substring(0,32),marker);return null;});
        }catch(Exception invalid){throw new IllegalStateException("Initialize file rejected ("+invalid.getClass().getSimpleName()+": "+invalid.getMessage()+")",invalid);}
    }
    private static String random(){byte[] value=new byte[16];new SecureRandom().nextBytes(value);return HexFormat.of().formatHex(value);}
}
