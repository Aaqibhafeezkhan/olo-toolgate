// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.*;

/** Direct mTLS job channel. Every message crosses the same durable authorization and replay rules as HTTP. */
@ApplicationScoped
public class EndpointSocket {
    @Inject EndpointService endpoints;
    @Inject ContractCodec codec;
    private static final String PATH="/api/control/v1/endpoint/socket";
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(EndpointSocket.class);
    private final AtomicInteger connections=new AtomicInteger();
    // A faster socket can arrive within the configured burst guard after the previous HTTP
    // poll. Check-in is idempotent; retry only its conflict, never an effect or
    // authorization denial. Sequence/digest validation still runs on the retry.
    static Store.Reply checkInWithJitter(java.util.function.Supplier<Store.Reply> request){
        return checkInWithJitter(request,ClientCheckInPolicy.QUICKSTART.burstGuardMs());
    }
    static Store.Reply checkInWithJitter(java.util.function.Supplier<Store.Reply> request,long burstGuardMs){
        try{return request.get();}
        catch(Failure failure){
            if(failure.code()!=ErrorCode.CONFLICT)throw failure;
            try{Thread.sleep(burstGuardMs);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw Failure.unavailable();}
            return request.get();
        }
    }
    void routes(@Observes Router router){
        router.get(PATH).order(-900).handler(context->{
            X509Certificate peer;
            try{
                if(!context.request().isSSL()||context.request().getHeader("Origin")!=null)throw new IllegalArgumentException();
                peer=(X509Certificate)context.request().sslSession().getPeerCertificates()[0];
            }catch(Exception invalid){context.response().setStatusCode(401).end();return;}
            if(connections.incrementAndGet()>128){connections.decrementAndGet();context.response().setStatusCode(503).end();return;}
            context.vertx().executeBlocking(()->{endpoints.verifySocketPeer(peer);return true;}).onComplete(auth->{
                if(auth.failed()){connections.decrementAndGet();context.response().setStatusCode(auth.cause() instanceof Failure failure?failure.status():503).end();return;}
                context.request().toWebSocket().onComplete(upgrade->{
                    if(upgrade.failed()){connections.decrementAndGet();return;}
                    channel(context.vertx(),upgrade.result(),peer,context.request().getHeader("X-ToolGate-System-Name"),context.request().remoteAddress().hostAddress().split("%",2)[0]);
                });
            });
        });
    }
    private void event(String state){LOG.info(codec.json(java.util.Map.of("service","control","event","client_socket","state",state)));}
    private void packet(String direction,String id,ClientSocketOperation operation,Long status,Object body){
        var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body);
        LOG.info(codec.json(java.util.Map.of("service","control","event","protocol_packet","transport","WEBSOCKET","direction",direction,"path",PATH,"requestId",id,"operation",operation,"status",status==null?0L:status,"message",PacketTelemetry.summary(node,0))));
    }
    private void channel(io.vertx.core.Vertx vertx,ServerWebSocket socket,X509Certificate peer,String systemName,String ipAddress){
        event("CONNECTED");
        var closed=new AtomicBoolean();var busy=new AtomicBoolean();var checking=new AtomicBoolean();
        var pong=new AtomicLong(System.nanoTime());var lastJob=new AtomicLong(System.nanoTime());
        var jobs=new java.util.concurrent.ConcurrentHashMap<String,Long>();
        long timer=vertx.setPeriodic(5000,ignored->{
            if(checking.compareAndSet(false,true))vertx.executeBlocking(()->{endpoints.verifySocketPeer(peer);return true;}).onComplete(auth->{
                checking.set(false);if(auth.failed()&&!closed.get())socket.close((short)1008,"Device authorization unavailable");
            });
            long now=System.nanoTime();jobs.entrySet().removeIf(entry->entry.getValue()<=System.currentTimeMillis());
            if(now-pong.get()>15000000000L){event("PONG_TIMEOUT");socket.close((short)1001,"Liveness timeout");}
            else if(jobs.isEmpty()&&now-lastJob.get()>35000000000L){event("IDLE_DISCONNECTED");socket.close((short)1000,"Job channel idle");}
            else {socket.writePing(Buffer.buffer("toolgate"));event("PING");}
        });
        socket.closeHandler(ignored->{if(closed.compareAndSet(false,true)){vertx.cancelTimer(timer);connections.decrementAndGet();event("DISCONNECTED");}});
        socket.exceptionHandler(ignored->socket.close());
        socket.pongHandler(value->{if("toolgate".equals(value.toString())){pong.set(System.nanoTime());event("PONG");}});
        socket.frameHandler(frame->{if(frame.isPing())event("PEER_PING");});
        socket.binaryMessageHandler(ignored->socket.close((short)1003,"Text protocol required"));
        socket.textMessageHandler(text->{
            if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536||!busy.compareAndSet(false,true)){socket.close((short)1008,"Bounded serialized requests required");return;}
            vertx.executeBlocking(()->{
                var request=codec.model(text,ClientSocketRequest.class);
                packet("RECEIVE",request.requestId(),request.operation(),null,request.body());
                Store.Reply reply;
                try{
                    endpoints.verifySocketPeer(peer);
                    String body=codec.json(request.body());
                    reply=switch(request.operation()){
                        case CHECK_IN -> checkInWithJitter(()->endpoints.checkIn(peer,body,request.requestId(),true,systemName,ipAddress),endpoints.checkInPolicy().burstGuardMs());
                        case AUTHORIZE -> endpoints.relay().authorize(peer,body);
                        case RESULT -> endpoints.relay().result(peer,body,request.requestId());
                    };
                    if(request.operation()==ClientSocketOperation.AUTHORIZE){var result=codec.model(reply.body(),RemoteToolAuthorizationAck.class);jobs.put(request.body().get("requestId").asText(),result.expiresAtUnixMs());lastJob.set(System.nanoTime());}
                    if(request.operation()==ClientSocketOperation.RESULT){jobs.remove(request.body().get("requestId").asText());lastJob.set(System.nanoTime());}
                    if(request.operation()==ClientSocketOperation.CHECK_IN){var ack=codec.model(reply.body(),EndpointCheckInAck.class);if(ack.task()!=null){jobs.put(ack.task().requestId(),ack.task().expiresAtUnixMs());lastJob.set(System.nanoTime());}}
                }catch(Failure failure){reply=new Store.Reply(failure.status(),codec.json(java.util.Map.of("code",failure.code())),0);}
                var json=new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String,com.fasterxml.jackson.databind.JsonNode> body=json.convertValue(json.readTree(reply.body()),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                var result=new ClientSocketReply(request.requestId(),(long)reply.status(),body);
                String document=codec.json(result);if(document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw Failure.validation();
                packet("SEND",request.requestId(),request.operation(),result.status(),body);
                return result;
            }).onComplete(result->{
                busy.set(false);
                if(result.failed()){socket.close((short)1008,"Invalid job channel message");return;}
                socket.writeTextMessage(codec.json(result.result()));
                if(result.result().status()==401||result.result().status()==403||result.result().status()==423)socket.close((short)1008,"Device authorization unavailable");
            });
        });
    }
}
