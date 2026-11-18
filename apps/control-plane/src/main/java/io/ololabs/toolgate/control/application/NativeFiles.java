// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** Protected filesystem target evidence; SDK outputs never enter this boundary. */
public final class NativeFiles {
    private NativeFiles(){}
    public static List<NativeFileCandidate> candidates(Store.Session tx,Codec codec,String device){
        var configuration=tx.endpointConfiguration(device);if(configuration==null)return List.of();
        var values=new ArrayList<NativeFileCandidate>();
        for(var tool:codec.model(configuration.localTools(),LocalToolCatalog.class).tools()){
            if(!tool.enabled())continue;
            var document=tx.packages().document(DeviceFileQualification.key(codec,tool.toolId(),tool.packageDigest()));
            if(document==null)continue;var proof=(JsonNode)codec.value(document);
            if(!"native-file-qualification/v1".equals(proof.path("format").asText()))continue;
            values.add(new NativeFileCandidate(device,tool.toolId(),tool.packageDigest(),proof.path("imageDigest").asText(),proof.path("writeAccess").asBoolean()));
            if(values.size()>256)throw Failure.conflict();
        }
        return List.copyOf(values);
    }
    public static void qualify(Store.Session tx,Codec codec,DirectoryService.Actor actor,NativeFileQualification v,long now,String requestId){
        if(!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))throw ManagementAccess.denied();
        if(v.expiresAtUnixMs()<=now||v.expiresAtUnixMs()>now+7*86400000L)throw Failure.validation();
        var group=tx.load().entries().get(Ids.Kind.DEVICE_GROUP.id(v.deviceGroupId()));
        if(group==null||!group.enabled()||!codec.model(group.document(),ControlDeviceGroup.class).deviceIds().contains(v.deviceId())
                ||candidates(tx,codec,v.deviceId()).stream().noneMatch(c->c.imageDigest().equals(v.imageDigest())))throw ManagementAccess.denied();
        String id=DirectoryService.digest(codec.json(v));tx.nativeFiles().qualify(id,v);
        tx.audit(actor.id(),"NATIVE_FILE_QUALIFIED",v.deviceId(),tx.load().revision(),requestId,id);
    }
    private static NativeFileStore.Scope scope(Store.Session tx,Codec codec,String invocation,String fence,long now,boolean requireOpen){
        var scope=tx.nativeFiles().scope(fence);
        if(scope==null||!scope.operation().equals(invocation))throw ManagementAccess.denied();
        var policy=tx.nativeFiles().qualification(scope.qualification());
        if(policy==null||!policy.enabled()||policy.value().expiresAtUnixMs()<=now)throw ManagementAccess.denied();
        var group=tx.load().entries().get(Ids.Kind.DEVICE_GROUP.id(policy.value().deviceGroupId()));
        if(group==null||!group.enabled()||!codec.model(group.document(),ControlDeviceGroup.class).deviceIds().contains(policy.value().deviceId()))throw ManagementAccess.denied();
        if(requireOpen&&(tx.nativeFiles().closed(fence)||tx.enterprise().manifestSeal(invocation,fence)!=null))throw Failure.conflict();
        return scope;
    }
    private static void path(String value){
        if(value==null||value.length()>1024||value.startsWith("/")||value.contains("\\")||value.contains(":"))throw Failure.validation();
        var parts=value.split("/",-1);if(parts.length>16)throw Failure.validation();
        for(var part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.startsWith(".")||!part.matches("[a-z0-9][a-z0-9._ -]{0,127}")||part.endsWith(" ")||part.endsWith("."))throw Failure.validation();
    }
    public static NativeFileEffectGrant begin(Store.Session tx,Codec codec,RemoteToolTask task,EnterpriseInvocation invocation,NativeFileEffectBegin v,long now){
        var scope=scope(tx,codec,invocation.id(),v.fencingToken(),now,true);
        if(!scope.writeAccess()||invocation.state()!=EnterpriseInvocationState.EXECUTING||!v.fencingToken().equals(invocation.reservedNonce()))throw ManagementAccess.denied();
        var lease=tx.enterprise().executionLease(invocation.id());
        if(lease==null||!lease.fencingToken().equals(v.fencingToken())||lease.expiresAt()<=now)throw ManagementAccess.denied();
        var intent=v.intent();path(intent.path());
        var arguments=task.request().arguments();
        if(arguments.values().stream().noneMatch(a->a.isTextual()&&a.asText().equals(intent.path())))throw ManagementAccess.denied();
        switch(intent.operation()){
            case WRITE->{if(intent.destination()!=null||intent.contentSha256()==null||arguments.values().stream().noneMatch(a->a.isTextual()&&("sha256:"+DirectoryService.digest(a.asText())).equals(intent.contentSha256())))throw ManagementAccess.denied();}
            case RENAME->{path(intent.destination());if(intent.contentSha256()!=null||intent.path().equals(intent.destination())||arguments.values().stream().noneMatch(a->a.isTextual()&&a.asText().equals(intent.destination())))throw ManagementAccess.denied();}
        }
        String digest=Canonical.digest((JsonNode)codec.value(codec.json(intent)));
        String effect="nf-"+DirectoryService.digest(codec.json(List.of(invocation.evaluation().context().tenantId(),invocation.id(),v.fencingToken(),v.ordinal())));
        tx.nativeFiles().begin(new NativeFileStore.Intent(effect,invocation.id(),v.fencingToken(),v.ordinal(),digest,intent),now);
        return new NativeFileEffectGrant(effect,digest);
    }
    public static void receipt(Store.Session tx,Codec codec,EnterpriseInvocation invocation,NativeFileEffectReceipt v,long now){
        scope(tx,codec,invocation.id(),v.fencingToken(),now,false);
        var intent=tx.nativeFiles().intent(v.effectId());
        if(intent==null||!intent.operation().equals(invocation.id())||!intent.attempt().equals(v.fencingToken()))throw ManagementAccess.denied();
        if(intent.value().operation()==NativeFileOperation.WRITE&&!v.afterSha256().equals(intent.value().contentSha256())
                ||intent.value().operation()==NativeFileOperation.RENAME&&!v.afterSha256().equals(v.beforeSha256()))throw Failure.validation();
        tx.nativeFiles().receipt(v,Canonical.digest((JsonNode)codec.value(codec.json(v))),now);
    }
    public static void close(Store.Session tx,Codec codec,EnterpriseInvocation invocation,NativeFileScopeClosed v,long now){
        var scope=scope(tx,codec,invocation.id(),v.fencingToken(),now,false);
        var intents=tx.nativeFiles().intents(invocation.id()).stream().filter(i->i.attempt().equals(v.fencingToken())).toList();
        if(scope.writeAccess()&&intents.isEmpty())throw Failure.conflict();
        if(intents.stream().anyMatch(i->tx.nativeFiles().receipts(i.effect()).size()!=1))throw Failure.conflict();
        tx.nativeFiles().close(v.fencingToken(),codec.json(v),now);
    }
}
