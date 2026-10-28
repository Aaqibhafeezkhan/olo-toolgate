// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.ControlServerSettings;
import java.time.Clock;
import java.util.*;

/** Trusted initialization layers, applied once inside the platform activation transaction. */
public final class TenantInitialization {
    private final ContractCodec codec;
    private final ClientCheckInPolicy checkIn;
    public TenantInitialization(ContractCodec codec,ClientCheckInPolicy checkIn){this.codec=codec;this.checkIn=checkIn;}
    public record Layer(String source,JsonNode snapshot,boolean common){}
    private record Composition(Directory directory,ControlServerSettings settings){}
    public void validateLayer(Layer layer,Ids.TenantId tenant){
        var node=layer.snapshot();if(!node.isObject())throw Failure.validation();
        if(node.has("formatVersion")&&(!node.get("formatVersion").isIntegralNumber()||node.get("formatVersion").asLong()!=2))throw Failure.validation();
        var allowed=new HashSet<>(List.of("formatVersion","revision","settings"));for(var kind:Ids.Kind.values())allowed.add(kind.snapshotKey());
        if(!layer.common())allowed.add("tenantId");
        for(var name:node.properties())if(!allowed.contains(name.getKey()))throw Failure.validation();
        if(layer.common()&&node.has("tenantId")||!layer.common()&&(!node.path("tenantId").asText().equals(tenant.value())))throw Failure.validation();
        var ids=new HashSet<Ids.RecordId>();
        for(var kind:Ids.Kind.values()){
            if(node.has(kind.snapshotKey())&&!node.get(kind.snapshotKey()).isArray())throw Failure.validation();
            for(var record:node.path(kind.snapshotKey())){
                if(layer.common()&&(Set.of(Ids.Kind.USER,Ids.Kind.DEVICE,Ids.Kind.IDENTITY_BINDING,Ids.Kind.WORKLOAD_BINDING,Ids.Kind.DEVICE_EVIDENCE).contains(kind)
                    ||GroupGraph.group(kind)&&!record.path(kind==Ids.Kind.TEAM?"userIds":kind==Ids.Kind.AGENT_GROUP?"agentIds":kind==Ids.Kind.TOOL_GROUP?"toolIds":"deviceIds").isEmpty()))throw Failure.validation();
                if(!ids.add(codec.entry(kind,codec.json(record)).id()))throw Failure.validation();
            }
        }
        if(node.has("settings")&&!node.get("settings").isObject())throw Failure.validation();
        if(layer.common()&&node.path("settings").path("deviceApproval").hasNonNull("ownerUserId"))throw Failure.validation();
    }
    public void apply(Store unit,Ids.TenantId tenant,String reviewed,List<FleetTrustKey> trust,List<Layer> layers,JsonNode environment){
        for(var layer:layers)validateLayer(layer,tenant);
        new ReviewedRecovery(unit,codec,Clock.systemUTC()).apply(reviewed,trust);
        unit.transaction(tenant,true,tx->{
            var before=tx.load();var composition=compose(before,layers,environment);
            if(!composition.directory().equals(before))tx.save(before,composition.directory());
            new ServerSettingsService(unit,codec,checkIn).initialize(tx,composition.settings(),"tenant-initial-settings");
            return null;
        });
    }
    public String preview(Ids.TenantId tenant,EnterpriseReviewedRecovery reviewed,List<Layer> layers,JsonNode environment){
        for(var layer:layers)validateLayer(layer,tenant);
        var initial=codec.input(codec.json(Map.of("snapshot",reviewed.authorization().snapshot(),"mode","REPLACE","dryRun",true)),false,tenant).directory();
        var entries=new HashMap<>(initial.entries());StandardAccessPresets.install(entries,codec);
        var seeded=new Directory(1,entries);var composition=compose(seeded,layers,environment);
        var access=new ManagementAccess(codec);
        var admin=reviewed.authorization().snapshot().users().stream().filter(ControlUser::enabled).filter(u->access.superAdministrator(seeded,u.id())).findFirst().orElseThrow(ManagementAccess::denied);
        if(!access.superAdministrator(composition.directory(),admin.id()))throw ManagementAccess.denied();
        return codec.json(Map.of("snapshot",codec.configuration(codec.snapshot(tenant,composition.directory(),false),false),"settings",composition.settings()));
    }
    private Composition compose(Directory before,List<Layer> layers,JsonNode environment){
            var entries=new HashMap<>(before.entries());
            var settings=(ObjectNode)codec.configuration(codec.json(ServerSettingsService.defaults(checkIn)),false);
            for(var layer:layers){
                for(var kind:Ids.Kind.values())for(var node:layer.snapshot().path(kind.snapshotKey())){
                    var entry=codec.entry(kind,codec.json(node));var old=entries.get(entry.id());
                    if(old!=null&&codec.revision(entry,old.revision()).equals(old))continue;
                    entries.put(entry.id(),codec.revision(entry,old==null?1:old.revision()+1));
                }
                if(layer.snapshot().has("settings"))merge(settings,layer.snapshot().get("settings"));
            }
            merge(settings,environment);
            var after=entries.equals(before.entries())?before:new Directory(before.revision()+1,entries);
            after.validate(512,1048576);codec.validatePolicies(after);
            var parsed=codec.model(codec.json(settings),ControlServerSettings.class);var owner=parsed.deviceApproval().ownerUserId();
            if(parsed.deviceApproval().autoApprove()&&owner==null)throw Failure.validation();
            if(owner!=null){var user=after.entries().get(Ids.Kind.USER.id(owner));if(user==null||!user.enabled())throw Failure.validation();}
            return new Composition(after,parsed);
    }
    /** Objects inherit omitted fields; scalars, arrays and explicit null replace. Final schemas validate nulls. */
    public static void merge(ObjectNode target,JsonNode layer){
        if(layer==null)return;if(!layer.isObject())throw Failure.validation();
        for(var field:layer.properties()){
            var existing=target.get(field.getKey());
            if(existing!=null&&existing.isObject()&&field.getValue().isObject())merge((ObjectNode)existing,field.getValue());
            else target.set(field.getKey(),field.getValue().deepCopy());
        }
    }
}
