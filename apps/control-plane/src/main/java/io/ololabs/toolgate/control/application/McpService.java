// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import java.time.Clock;
import java.util.*;

/** Device relay uses the same durable invocation authority as every other effect path. */
public final class McpService {
    private final Store store;private final Codec codec;private final EndpointService endpoints;private final EnterpriseOperations operations;
    private final Clock clock;private final Ids.TenantId tenant;private final String server;
    public McpService(Store store,Codec codec,EndpointService endpoints,EnterpriseOperations operations,Clock clock,Ids.TenantId tenant,String server){this.store=store;this.codec=codec;this.endpoints=endpoints;this.operations=operations;this.clock=clock;this.tenant=tenant;this.server=server;}
    private Failure forbidden(){return ManagementAccess.denied();}
    private long now(Store.Session tx){long now=clock.millis();if(now<0||tx.approvalClock(now)>now)throw Failure.unavailable();return now;}
    private void gateway(DirectoryService.Actor actor,boolean gateway){if(!gateway||actor.admin()||actor.userId()!=null||!actor.tenant().equals(tenant))throw forbidden();}
    private Store.Reply reply(Object value){return new Store.Reply(200,codec.json(value),0);}
    private RemoteToolRecord record(McpStore.Row row){return codec.model(row.record(),RemoteToolRecord.class);}
    private RemoteToolTask task(McpStore.Row row){return codec.model(row.task(),RemoteToolTask.class);}
    private EnterpriseInvocation invocation(Store.Session tx,String id){var i=tx.enterprise().invocation(Ids.valid(id));if(i==null)throw forbidden();return i;}
    private EndpointDeviceRecord device(Store.Session tx,RequestContext context,String deviceId){
        if(!tenant.value().equals(context.tenantId())||deviceId==null)throw forbidden();var row=tx.endpoint(deviceId);if(row==null)throw forbidden();
        var d=codec.model(row.document(),EndpointDeviceRecord.class);endpoints.active(tx,d);return d;
    }
    private DevicePlacement placement(){return new DevicePlacement(codec,endpoints.checkInPolicy());}
    private LocalToolCatalog local(Store.Session tx,EndpointDeviceRecord device){
        var saved=tx.endpointConfiguration(device.deviceId());new EndpointAdoptions(codec).poll(tx,device,server,saved==null?null:saved.acknowledgedDigest(),null);
        return codec.model(tx.endpointConfiguration(device.deviceId()).localTools(),LocalToolCatalog.class);
    }
    private void installed(Store.Session tx,EnterpriseInvocation i,AuthorizationRequest request){
        var row=tx.endpoint(i.evaluation().context().deviceId());if(row==null)throw forbidden();var d=codec.model(row.document(),EndpointDeviceRecord.class);endpoints.active(tx,d);
        var e=i.evaluation();if(local(tx,d).tools().stream().noneMatch(t->t.enabled()&&t.toolId().equals(request.toolId())&&t.action().equals(request.action())&&t.toolDigest().equals(e.toolDigest())&&t.packageDigest().equals(e.packageDigest())))throw forbidden();
    }
    private void scope(Store.Session tx,DirectoryService.Actor actor,EnterpriseInvocation i,String action){
        actor.requireAdmin();if(!actor.tenant().equals(tenant)||actor.userId()==null)throw forbidden();
        var b=codec.model(tx.load().entries().get(Ids.Kind.BINDING.id(i.evaluation().context().bindingId())).document(),ControlExecutionBinding.class);var access=new ManagementAccess(codec);
        access.require(tx.load(),actor.userId(),action,Ids.Kind.TOOL_GROUP,b.toolGroupId(),now(tx));access.require(tx.load(),actor.userId(),action,Ids.Kind.DEVICE_GROUP,b.deviceGroupId(),now(tx));
    }
    public Store.Reply catalog(DirectoryService.Actor actor,boolean gateway,String body){return catalog(actor,gateway,body,null);}
    /** chosen is the device a person picked in the console; Gateway callers are placed by Control. */
    Store.Reply catalog(DirectoryService.Actor actor,boolean gateway,String body,String chosen){gateway(actor,gateway);var c=codec.model(body,RequestContext.class);
        return store.transaction(tenant,true,tx->{long now=now(tx);if(!tenant.value().equals(c.tenantId()))throw forbidden();
            if(chosen!=null&&!placement().member(tx,c.bindingId(),chosen))throw forbidden();var placed=chosen!=null?chosen:placement().place(tx,c,now);
            if(placed==null||tx.endpoint(placed)==null)return reply(new LocalToolCatalog(List.of()));
            var d=device(tx,c,placed);if(endpoints.checkInPolicy().offline(d.lastSeenUnixMs(),now))return reply(new LocalToolCatalog(List.of()));var usage=new HashMap<String,Long>();var hints=new DiscoveryIndex(codec).rules(tenant,tx.load(),c,placed,now,budget->usage.computeIfAbsent(budget,id->tx.enterprise().budgetUsage(id,Math.max(0,now-60000),c.requestId())));var tools=new ArrayList<BuiltinToolInfo>();
            for(var t:local(tx,d).tools())if(t.enabled()&&hints.stream().anyMatch(rule->rule.toolId().equals(t.toolId())&&rule.action().equals(t.action()))){
                var entry=tx.load().entries().get(Ids.Kind.TOOL.id(t.toolId()));if(entry==null||!entry.enabled())continue;var tool=codec.model(entry.document(),ControlTool.class);
                if(t.toolDigest().equals(new EnterpriseEvaluator(codec).toolDigest(tx.load(),tool))&&t.packageDigest().equals(tool.packageDigest()))tools.add(t);
            }return reply(new LocalToolCatalog(tools));});}
    public Store.Reply submit(DirectoryService.Actor actor,boolean gateway,String body,String requestId){gateway(actor,gateway);var submission=codec.model(body,RemoteToolSubmission.class);
        return store.transaction(tenant,true,tx->{long now=now(tx);var i=invocation(tx,submission.invocationId());if(!i.id().equals(submission.context().requestId()))throw forbidden();
            var evaluation=new RuntimeAccess(codec).evaluation(tenant,tx.load(),submission.context(),i.evaluation().context().deviceId(),submission.request(),i.evaluation().toolDigest(),i.evaluation().packageDigest(),now);
            var e=i.evaluation();if(!evaluation.context().equals(e.context())||!evaluation.argumentsDigest().equals(e.argumentsDigest())||!evaluation.resources().equals(e.resources())||!evaluation.toolId().equals(e.toolId())||!evaluation.action().equals(e.action()))throw forbidden();
            operations.requireFresh(tenant,tx,i);installed(tx,i,submission.request());var previous=tx.mcp().get(i.id());
            if(previous!=null){if(!task(previous).request().equals(submission.request()))throw Failure.conflict();return reply(new RemoteToolResponse(record(previous),Set.of(RemoteToolState.DONE,RemoteToolState.FAILED).contains(record(previous).state())&&previous.result()!=null?codec.model(previous.result(),RemoteToolResult.class):null));}
            if(i.state()!=EnterpriseInvocationState.QUEUED||submission.expiresAtUnixMs()<=now||submission.expiresAtUnixMs()>now+30000)throw Failure.conflict();
            var d=device(tx,submission.context(),e.context().deviceId());if(endpoints.checkInPolicy().offline(d.lastSeenUnixMs(),now))throw Failure.unavailable();
            long expires=Math.min(i.expiresAtUnixMs(),submission.expiresAtUnixMs());if(d.connectionExpiresAtUnixMs()!=null)expires=Math.min(expires,d.connectionExpiresAtUnixMs());
            var reserved=operations.reserveInSession(tenant,tx,new EnterpriseReservationRequest(i.id(),i.revision()),actor.id());
            var task=new RemoteToolTask(i.id(),UUID.randomUUID().toString(),submission.request(),expires,reserved.invocation(),reserved.permit());
            if(codec.json(task).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw Failure.validation();
            tx.mcp().prune(Math.max(0,now-86400000));if(tx.mcp().count()>=1000||tx.mcp().pending(d.deviceId(),17).size()>=16)throw Failure.conflict();
            var record=new RemoteToolRecord(i.id(),d.deviceId(),e.context().agentId(),e.toolId(),RemoteToolState.WAITING_FOR_POLL,now,expires,null,null,null,null);
            tx.mcp().save(new McpStore.Row(record.requestId(),record.deviceId(),record.state().name(),expires,codec.json(record),codec.json(task),null));
            tx.audit(actor.id(),"MCP_RECEIVED",record.requestId(),i.revision(),requestId,i.requestDigest());return reply(new RemoteToolResponse(record,null));});}
    private RemoteToolRecord transition(Store.Session tx,McpStore.Row row,RemoteToolState state,long now,ErrorCode error,String result,String requestId){
        var old=record(row);var next=new RemoteToolRecord(old.requestId(),old.deviceId(),old.agentId(),old.toolId(),state,old.receivedAtUnixMs(),old.expiresAtUnixMs(),state==RemoteToolState.SUBMITTED?Long.valueOf(now):old.submittedAtUnixMs(),state==RemoteToolState.RESPONSE_RECEIVED?Long.valueOf(now):old.responseAtUnixMs(),Set.of(RemoteToolState.DONE,RemoteToolState.FAILED,RemoteToolState.EXPIRED).contains(state)?Long.valueOf(now):old.completedAtUnixMs(),error);
        tx.mcp().save(new McpStore.Row(row.id(),row.deviceId(),state.name(),row.expiresAt(),codec.json(next),row.task(),result));
        tx.audit("mcp-relay","MCP_"+state.name(),row.id(),invocation(tx,row.id()).revision(),requestId,invocation(tx,row.id()).requestDigest());return next;
    }
    public RemoteToolTask poll(Store.Session tx,EndpointDeviceRecord device,long now,String requestId){
        for(var row:tx.mcp().pending(device.deviceId(),16)){
            if(row.expiresAt()<=now){transition(tx,row,RemoteToolState.EXPIRED,now,ErrorCode.TIMEOUT,null,requestId);continue;}
            var i=invocation(tx,row.id());if(i.state()==EnterpriseInvocationState.EXECUTING)continue; // Never dispatch consumed work after a crash.
            var task=task(row);try{operations.requireFresh(tenant,tx,i);installed(tx,i,task.request());if(i.state()!=EnterpriseInvocationState.RESERVED)throw forbidden();}
            catch(Failure revoked){if(revoked.status()!=403)throw revoked;transition(tx,row,RemoteToolState.FAILED,now,ErrorCode.FORBIDDEN,null,requestId);continue;}
            var nonce=tx.enterprise().nonce(i.reservedNonce());if(nonce==null||nonce.consumedAt()!=null)throw forbidden();
            if(nonce.expiresAt()<=now){var refreshed=operations.reserveInSession(tenant,tx,new EnterpriseReservationRequest(i.id(),i.revision()),"mcp-relay");
                task=new RemoteToolTask(task.requestId(),task.leaseId(),task.request(),task.expiresAtUnixMs(),refreshed.invocation(),refreshed.permit());
                tx.mcp().save(new McpStore.Row(row.id(),row.deviceId(),row.state(),row.expiresAt(),row.record(),codec.json(task),row.result()));row=tx.mcp().get(row.id());}
            if(record(row).state()==RemoteToolState.WAITING_FOR_POLL)transition(tx,row,RemoteToolState.SUBMITTED,now,null,null,requestId);return task;
        }return null;
    }
    public Store.Reply authorize(java.security.cert.X509Certificate peer,String body){var request=codec.model(body,RemoteToolAuthorization.class);
        return store.transaction(tenant,true,tx->{long now=now(tx);var d=endpoints.authenticate(tx,peer,now);var row=tx.mcp().get(request.requestId());if(row==null||!row.deviceId().equals(d.deviceId())||!row.state().equals("SUBMITTED")||row.expiresAt()<=now||!task(row).leaseId().equals(request.leaseId())||!task(row).request().equals(request.request()))throw forbidden();
            var i=invocation(tx,row.id());installed(tx,i,request.request());operations.checkpoint(tenant,tx,d.deviceId(),i,request.request());return reply(new RemoteToolAuthorizationAck(Math.min(row.expiresAt(),i.expiresAtUnixMs())));});}
    public Store.Reply result(java.security.cert.X509Certificate peer,String body,String requestId){var result=codec.model(body,RemoteToolResult.class);if((result.output()==null)==(result.error()==null)||codec.json(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();
        return store.transaction(tenant,true,tx->{long now=now(tx);var d=endpoints.authenticate(tx,peer,now);var row=tx.mcp().get(result.requestId());if(row==null||!row.deviceId().equals(d.deviceId())||!task(row).leaseId().equals(result.leaseId()))throw forbidden();
            if(row.result()!=null){if(!codec.model(row.result(),RemoteToolResult.class).equals(result))throw Failure.conflict();return reply(record(row));}
            if(!row.state().equals("SUBMITTED"))throw Failure.conflict();var i=invocation(tx,row.id());
            operations.reportInSession(tenant,tx,d.deviceId(),new EnterpriseEffectReport(i.id(),i.revision(),result.error()==null?EnterpriseInvocationState.SUCCEEDED:EnterpriseInvocationState.OUTCOME_UNKNOWN,result.error()==null?i.evaluation().resources():i.completedResources(),result.output()==null?null:DirectoryService.digest(codec.json(result.output()))));
            return reply(transition(tx,row,RemoteToolState.RESPONSE_RECEIVED,now,result.error(),codec.json(result),requestId));});}
    public Store.Reply response(DirectoryService.Actor actor,boolean gateway,String body,String requestId){gateway(actor,gateway);var c=codec.model(body,RequestContext.class);
        return store.transaction(tenant,true,tx->{long now=now(tx);var row=tx.mcp().get(c.requestId());if(row==null)throw forbidden();var task=task(row);var i=invocation(tx,row.id());
            var evaluation=new RuntimeAccess(codec).evaluation(tenant,tx.load(),c,i.evaluation().context().deviceId(),task.request(),i.evaluation().toolDigest(),i.evaluation().packageDigest(),now);if(!evaluation.context().equals(i.evaluation().context()))throw forbidden();
            device(tx,c,i.evaluation().context().deviceId());operations.requireFresh(tenant,tx,i);var record=record(row);
            if(record.state()==RemoteToolState.RESPONSE_RECEIVED)record=transition(tx,row,record.error()==null?RemoteToolState.DONE:RemoteToolState.FAILED,now,record.error(),row.result(),requestId);
            else if((record.state()==RemoteToolState.WAITING_FOR_POLL||record.state()==RemoteToolState.SUBMITTED)&&row.expiresAt()<=now)record=transition(tx,row,RemoteToolState.EXPIRED,now,ErrorCode.TIMEOUT,null,requestId);
            return reply(new RemoteToolResponse(record,Set.of(RemoteToolState.DONE,RemoteToolState.FAILED).contains(record.state())&&row.result()!=null?codec.model(row.result(),RemoteToolResult.class):null));});}
    public Store.Reply page(DirectoryService.Actor actor,String requestId){return store.transaction(tenant,true,tx->{var items=new ArrayList<RemoteToolRecord>();for(var row:tx.mcp().page(100))try{scope(tx,actor,invocation(tx,row.id()),"read");items.add(record(row));}catch(Failure denied){if(denied.status()!=403)throw denied;}return reply(new RemoteToolPage(items));});}
    public Store.Reply inspect(DirectoryService.Actor actor,String requestId){return store.transaction(tenant,true,tx->{var row=tx.mcp().get(Ids.valid(requestId));if(row==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Request not found");var i=invocation(tx,row.id());scope(tx,actor,i,"read");operations.requireFresh(tenant,tx,i);
        var progress=record(row);var result=Set.of(RemoteToolState.DONE,RemoteToolState.FAILED).contains(progress.state())&&row.result()!=null?codec.model(row.result(),RemoteToolResult.class):null;return reply(new RemoteToolInspection(progress,result==null?null:result.output()));});}
}
