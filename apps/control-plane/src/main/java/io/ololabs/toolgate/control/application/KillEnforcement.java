// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.util.List;

/** Current kill state and pinned attempt facts are checked in the same serialized authority transaction. */
public final class KillEnforcement {
    private KillEnforcement(){}
    public static boolean currentEpoch(Store.Session tx,long previous){
        long current=tx.enterprise().authorizationEpoch();
        return previous==current||tx.kills().killOnlySince(previous,current);
    }
    static KillPolicy.Subject resolve(Store.Session tx,Codec codec,EnterpriseEvaluation evaluation){
        return resolve(tx,codec,evaluation.toolId(),evaluation.packageDigest(),evaluation.context().deviceId());
    }
    static KillPolicy.Subject resolve(Store.Session tx,Codec codec,String tool,String packageDigest,String device){
        String digest="sha256:"+packageDigest;
        String packageId=null; // Only verified package metadata may establish this fact.
        String publisher=null,image=null,pool="";
        var version=tx.packages().version(digest);
        if(version!=null){
            var manifest=((JsonNode)codec.value(version.document())).path("manifest");
            if(manifest.path("id").isTextual())packageId=manifest.path("id").asText();
            if(manifest.path("publisher").isTextual())publisher=manifest.path("publisher").asText();
            JsonNode declaration=null;
            for(var item:manifest.path("tools"))if(item.path("descriptor").path("toolId").asText().equals(tool)){
                if(declaration!=null)throw ManagementAccess.denied();declaration=item;
            }
            if(declaration!=null)for(var runtime:manifest.path("runtimes"))
                if(runtime.path("runtimeId").asText().equals(declaration.path("runtimeId").asText())){
                    if(image!=null)throw ManagementAccess.denied();
                    image=runtime.path("baseImageDigest").asText("");
                }
        }
        var registration=tx.packages().toolHost(device);
        if(registration!=null){var node=(JsonNode)codec.value(registration);pool=node.path("poolId").isTextual()?node.path("poolId").asText():null;}
        return new KillPolicy.Subject(packageId,digest,publisher,tool,pool,image);
    }
    static boolean killed(Store.Session tx,Codec codec,String tool,String digest,String device){
        var events=tx.kills().effective();if(events.isEmpty())return false;
        var subject=resolve(tx,codec,tool,digest,device);
        for(var event:events){var kill=codec.model(event.document(),KillEvent.class);
            if(KillPolicy.blocks(kill.mode(),kill.scope(),subject,false))return true;}
        return false;
    }
    public static void capture(Store.Session tx,Codec codec,EnterpriseInvocation invocation){
        if(invocation.reservedNonce()==null)throw ManagementAccess.denied();
        var existing=tx.kills().scope(invocation.reservedNonce());
        if(existing!=null){bound(existing,invocation);return;}
        tx.kills().bindScope(new KillStore.Scope(invocation.id(),invocation.reservedNonce(),invocation.evaluation().context().deviceId(),
            resolve(tx,codec,invocation.evaluation())));
    }
    private static void bound(KillStore.Scope scope,EnterpriseInvocation i){
        if(!scope.operation().equals(i.id())||!scope.attempt().equals(i.reservedNonce())
            ||!scope.device().equals(i.evaluation().context().deviceId())||!scope.subject().toolId().equals(i.evaluation().toolId())
            ||!scope.subject().packageDigest().equals("sha256:"+i.evaluation().packageDigest()))throw ManagementAccess.denied();
    }
    static void requireNew(Store.Session tx,Codec codec,EnterpriseEvaluation evaluation){
        var events=tx.kills().effective();if(events.isEmpty())return;
        require(events,codec,resolve(tx,codec,evaluation),false);
    }
    static void require(Store.Session tx,Codec codec,EnterpriseInvocation invocation){
        var events=tx.kills().effective();if(events.isEmpty())return;
        boolean running=invocation.state()==EnterpriseInvocationState.EXECUTING;
        var scope=invocation.reservedNonce()==null?null:tx.kills().scope(invocation.reservedNonce());
        KillPolicy.Subject subject;
        if(scope!=null){bound(scope,invocation);subject=scope.subject();}
        else subject=resolve(tx,codec,invocation.evaluation());
        require(events,codec,subject,running);
    }
    private static void require(List<KillStore.Event> events,Codec codec,KillPolicy.Subject subject,boolean running){
        for(var event:events){
            var model=codec.model(event.document(),KillEvent.class);
            if(KillPolicy.blocks(model.mode(),model.scope(),subject,running))throw ManagementAccess.denied();
        }
    }
}
