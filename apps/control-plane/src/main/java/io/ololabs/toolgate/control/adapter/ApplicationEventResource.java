// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.contracts.ErrorCode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.sse.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded authenticated polling and resumable SSE for one authorized source. */
@Path("/api/control/v1/application-events") @io.quarkus.security.Authenticated @io.smallrye.common.annotation.Blocking
public class ApplicationEventResource {
    @Inject VerifiedActors actors; @Inject ApplicationIntegrationBootstrap integrations; @Inject ContractCodec codec; @Inject PostgresStore store;
    @Inject io.vertx.ext.web.RoutingContext routing;
    private static final Semaphore SLOTS=new Semaphore(16);
    private static final Map<String,Integer> USERS=new HashMap<>();
    private static Set<ApplicationEventKind> kinds(ApplicationEventSource source,String value) {
        if(value==null)return source==ApplicationEventSource.WORKFLOW?Set.of(ApplicationEventKind.PROGRESS,ApplicationEventKind.RESULT):Set.of(ApplicationEventKind.TRIGGER_STATUS);
        try {var result=Arrays.stream(value.split(",",-1)).map(ApplicationEventKind::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());if(result.isEmpty())throw Failure.validation();return result;}
        catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    private static ApplicationEventSource source(String value){try{return ApplicationEventSource.valueOf(value);}catch(IllegalArgumentException invalid){throw Failure.validation();}}
    @GET @Path("{source}/{id}") @Produces("application/json")
    public Response page(@PathParam("source")String type,@PathParam("id")String id,@QueryParam("after")@DefaultValue("0")long after,@QueryParam("kinds")String selected) {
        var actor=actors.human();var source=source(type);
        var reply=integrations.events(actor.tenant()).page(actor,source,id,after,kinds(source,selected));
        return Response.ok(reply.body()).header("Cache-Control","no-store").build();
    }
    @GET @Path("{source}/{id}/stream") @Produces("text/event-stream")
    public void stream(@PathParam("source")String type,@PathParam("id")String id,@QueryParam("after")@DefaultValue("0")long after,
                       @QueryParam("kinds")String selected,@HeaderParam("Last-Event-ID")String last,@Context SseEventSink sink,@Context Sse sse) {
        var actor=actors.human();var source=source(type);var selection=kinds(source,selected);var service=integrations.events(actor.tenant());
        long cursor=after;
        if(last!=null){if(!last.matches("[0-9]{1,16}"))throw Failure.validation();try{cursor=Long.parseLong(last);}catch(NumberFormatException invalid){throw Failure.validation();}}
        service.page(actor,source,id,cursor,selection); // Deny before committing a streaming response.
        String stamp=store.transaction(actor.tenant(),false,tx->new WorkflowUserAccess(codec).identities(tx.load(),actor.userId()));
        String owner=actor.tenant().value()+":"+actor.userId();
        synchronized(USERS){if(USERS.getOrDefault(owner,0)>=2||!SLOTS.tryAcquire())throw Failure.unavailable();USERS.merge(owner,1,Integer::sum);}
        routing.response().putHeader("Cache-Control","no-store").putHeader("X-Accel-Buffering","no");
        long initialCursor=cursor;
        Thread.ofVirtual().name("toolgate-application-events").start(()->{
            long next=initialCursor,deadline=Math.min(System.currentTimeMillis()+60000,actor.sessionExpiresAt()==null?0:actor.sessionExpiresAt());
            try {
                while(!sink.isClosed()&&System.currentTimeMillis()<deadline) {
                    String current=store.transaction(actor.tenant(),false,tx->new WorkflowUserAccess(codec).identities(tx.load(),actor.userId()));
                    if(!stamp.equals(current))throw ManagementAccess.denied();
                    var reply=service.page(actor,source,id,next,selection);
                    var page=codec.model(reply.body(),ApplicationEventPage.class);
                    for(var event:page.items()) {
                        if(System.currentTimeMillis()>=deadline)break;
                        store.transaction(actor.tenant(),false,tx->{
                            if(!stamp.equals(new WorkflowUserAccess(codec).identities(tx.load(),actor.userId())))throw ManagementAccess.denied();
                            service.authorize(tx,actor,source,id,selection,true);return null;
                        });
                        sink.send(sse.newEventBuilder().id(Long.toString(event.sequence())).name(event.kind().name())
                            .mediaType(MediaType.APPLICATION_JSON_TYPE).data(ApplicationEvent.class,event).build()).toCompletableFuture().get(3,TimeUnit.SECONDS);
                    }
                    next=page.nextCursor();
                    if(page.items().isEmpty())sink.send(sse.newEventBuilder().comment("heartbeat").build()).toCompletableFuture().get(3,TimeUnit.SECONDS);
                    Thread.sleep(1000);
                }
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(Exception closed){ /* Closed, revoked, expired or slow connections require a fresh authorized reconnect. */ }
            finally{try{sink.close();}finally{synchronized(USERS){int count=USERS.getOrDefault(owner,1)-1;if(count==0)USERS.remove(owner);else USERS.put(owner,count);SLOTS.release();}}}
        });
    }
}
