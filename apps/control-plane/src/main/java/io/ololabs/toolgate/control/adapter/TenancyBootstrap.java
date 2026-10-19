// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.FleetTrustKey;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.eclipse.microprofile.config.Config;

/** Deployment provisioning, before tenant runtime startup observers. No login or request can invoke it. */
@ApplicationScoped
public class TenancyBootstrap {
    @Inject Config config; @Inject TenantRegistry registry; @Inject TenancyConfiguration tenancy;
    @Inject ContractCodec codec; @Inject ConfigurationImportBootstrap inputs;
    private record Prepared(InitializationManifest.Spec spec,Ids.TenantId id,List<FleetTrustKey> trust,boolean initialized,String effectiveDigest){}
    void start(@Observes @jakarta.annotation.Priority(8) StartupEvent event){
        tenancy.validate();
        try{
            var manifestPath=config.getOptionalValue("toolgate.initialize.manifest",String.class);
            InitializationManifest manifest;
            if(manifestPath.isPresent())manifest=InitializationManifest.load(Path.of(manifestPath.get()),codec);
            else{
                if(tenancy.mode().equals("subdomain"))throw new IllegalStateException("Subdomain mode requires an initialization manifest");
                var packet=config.getOptionalValue("toolgate.control.recovery.packet-path",String.class);
                if(packet.isEmpty()){
                    registry.allocate(tenancy.defaultAlias(),"Default organization");return;
                }
                var layers=new ArrayList<TenantInitialization.Layer>();
                var initial=config.getOptionalValue("toolgate.control.initialize.path",String.class);
                if(initial.isPresent())layers.add(new TenantInitialization.Layer("default-initialization",codec.configuration(ProtectedCustody.text(Path.of(initial.get()),2097152),initial.get().endsWith(".yaml")||initial.get().endsWith(".yml")),false));
                var hosts=Arrays.stream(config.getOptionalValue("toolgate.single-tenant-hosts",String.class).orElse("localhost,127.0.0.1,::1").split(",",-1)).map(TenantHosts::normalize).distinct().toList();
                manifest=new InitializationManifest(tenancy.defaultAlias(),List.of(new InitializationManifest.Spec(tenancy.defaultAlias(),"Default organization",hosts,
                    ProtectedCustody.text(Path.of(packet.get()),2097152),ProtectedCustody.text(Path.of(config.getValue("toolgate.control.recovery.trust-path",String.class)),16384),List.copyOf(layers))));
            }
            tenancy.manifestDefault(manifest.defaultAlias());
            var checkIn=ClientCheckInPolicy.configure(config.getOptionalValue("toolgate.control.client-check-in.check-in-ms",Long.class).orElse(null),config.getOptionalValue("toolgate.control.client-check-in.jitter-pct",Integer.class).orElse(null),config.getOptionalValue("toolgate.control.client-check-in.offline-after-ms",Long.class).orElse(null));
            var initializer=new TenantInitialization(codec,checkIn);var prepared=new ArrayList<Prepared>();var environment=inputs.environment();
            // No registration/configuration writes before all newly requested inputs validate.
            for(var spec:manifest.tenants()){
                var packet=codec.configuration(spec.packet(),false);var id=new Ids.TenantId(packet.path("authorization").path("tenantId").asText());
                var trustNode=codec.configuration(spec.trust(),false);
                if(!trustNode.isObject()||trustNode.size()!=1||!trustNode.path("keys").isArray()||trustNode.path("keys").isEmpty()||trustNode.path("keys").size()>4)throw Failure.validation();
                var trust=new ArrayList<FleetTrustKey>();for(var key:trustNode.path("keys"))trust.add(codec.model(codec.json(key),FleetTrustKey.class));
                var existing=registry.find(spec.alias());if(existing!=null&&!existing.id().equals(id))throw Failure.conflict();
                boolean initialized=existing!=null&&registry.initialized(id);
                String effectiveDigest=null;
                if(!initialized){var reviewed=new ReviewedRecovery(null,codec,Clock.systemUTC()).validate(spec.packet(),trust);effectiveDigest=DirectoryService.digest(initializer.preview(id,reviewed,spec.layers(),environment));}
                prepared.add(new Prepared(spec,id,List.copyOf(trust),initialized,effectiveDigest));
            }
            for(var item:prepared){
                if(item.initialized())continue;
                var spec=item.spec();registry.allocate(spec.alias(),spec.name(),item.id());
                var sources=new ArrayList<Map<String,String>>();sources.add(StandardAccessPresets.provenance(codec));sources.add(Map.of("source","reviewed-administrator","digest",DirectoryService.digest(spec.packet())));
                for(var layer:spec.layers())sources.add(Map.of("source",layer.source(),"digest",DirectoryService.digest(codec.json(layer.snapshot()))));
                sources.add(Map.of("source","initial-environment","digest",DirectoryService.digest(codec.json(environment))));
                String provenance=codec.json(sources);
                try{registry.initialize(item.id(),item.effectiveDigest(),provenance,spec.hosts(),unit->initializer.apply(unit,item.id(),spec.packet(),item.trust(),spec.layers(),environment));}
                catch(RuntimeException invalid){org.jboss.logging.Logger.getLogger(TenancyBootstrap.class).error(codec.json(Map.of("event","tenant_initialization_failed","tenantId",item.id().value(),"code","INITIALIZATION_FAILED")));}
            }
        }catch(Exception invalid){throw new IllegalStateException("Tenant initialization manifest or protected provisioning material rejected",invalid);}
    }
}
