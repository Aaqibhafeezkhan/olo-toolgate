// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.CredentialKind;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.*;
/** Direct human operations preserve verified caller identity through the ordinary durable device relay. */
public final class HumanRuntime {
    private final Store store;private final Codec codec;private final EnterpriseOperations operations;private final McpService relay;private final ServerPackages hosts;
    public HumanRuntime(Store store,Codec codec,EnterpriseOperations operations,McpService relay,ServerPackages hosts){this.store=store;this.codec=codec;this.operations=operations;this.relay=relay;this.hosts=hosts;}
    private RequestContext context(DirectoryService.Actor actor,long epoch,String key,String binding){if(actor.userId()==null||epoch<1)throw ManagementAccess.denied();return context("human-"+DirectoryService.digest(actor.id()+"\n"+Ids.valid(key)).substring(0,40),actor,epoch,binding);}
    /** A console session context. The device the person chose travels beside it, never inside it. */
    private static RequestContext context(String id,DirectoryService.Actor actor,long epoch,String binding){return new RequestContext(id,actor.tenant().value(),RequestMode.HUMAN,CredentialKind.STATIC_TOKEN,null,actor.userId(),null,null,List.of(),epoch,null,null,null,binding,null);}
    private DirectoryService.Actor internal(DirectoryService.Actor actor){return new DirectoryService.Actor(actor.tenant(),actor.id(),false);}
    public Store.Reply catalog(DirectoryService.Actor actor,long epoch,String body){var target=codec.model(body,EnterpriseHumanTarget.class);return relay.catalog(internal(actor),true,codec.json(context(actor,epoch,"human-discovery",target.bindingId())),target.deviceId());}
    public Store.Reply invoke(DirectoryService.Actor actor,long epoch,String body,String key){var request=codec.model(body,EnterpriseHumanRequest.class);var context=context(actor,epoch,key,request.bindingId());var catalog=codec.model(relay.catalog(internal(actor),true,codec.json(context),request.deviceId()).body(),LocalToolCatalog.class);var tools=catalog.tools().stream().filter(t->t.enabled()&&t.toolId().equals(request.request().toolId())&&t.action().equals(request.request().action())).toList();if(tools.size()!=1)throw ManagementAccess.denied();var tool=tools.getFirst();
        var invocation=codec.model(operations.submitHuman(actor,new EnterpriseInvocationRequest(context,request.request(),tool.toolDigest(),tool.packageDigest(),null),request.deviceId()).body(),EnterpriseInvocation.class);
        if(invocation.state()==EnterpriseInvocationState.PENDING_APPROVAL)return new Store.Reply(202,codec.json(new EnterpriseHumanOutcome(invocation,null)),invocation.revision());
        // A tool placed on a Tool Host runs from its verified package there, through the same permit and relayed dispatch as a
        // Gateway call; its run events and result arrive in the invocation's stream.
        if(hosts.toolHost(actor.tenant(),request.deviceId())){hosts.humanDispatch(actor,invocation.id(),request.request().arguments());var queued=codec.model(operations.get(actor,invocation.id()).body(),EnterpriseInvocation.class);return new Store.Reply(200,codec.json(new EnterpriseHumanOutcome(queued,null)),queued.revision());}
        var dispatch=codec.model(relay.submit(internal(actor),true,codec.json(new RemoteToolSubmission(request.request(),Math.min(invocation.expiresAtUnixMs(),System.currentTimeMillis()+30000),context,invocation.id())),context.requestId()).body(),RemoteToolResponse.class);
        var current=codec.model(operations.get(actor,invocation.id()).body(),EnterpriseInvocation.class);
        return new Store.Reply(200,codec.json(new EnterpriseHumanOutcome(current,dispatch)),current.revision());
    }
    public Store.Reply result(DirectoryService.Actor actor,long epoch,String id){var invocation=codec.model(operations.get(actor,id).body(),EnterpriseInvocation.class);var e=invocation.evaluation();if(!actor.userId().equals(e.context().userId())||e.context().mode()!=RequestMode.HUMAN||e.context().sessionEpoch()!=epoch)throw ManagementAccess.denied();if(hosts.toolHost(actor.tenant(),e.context().deviceId()))return hosts.humanResult(actor,epoch,id);var c=context(id,actor,epoch,e.context().bindingId());return relay.response(internal(actor),true,codec.json(c),id);}
}
