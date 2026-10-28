// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TenantRoutingTest {
    private static org.eclipse.microprofile.config.Config config(){
        return config(Map.of());
    }
    private static org.eclipse.microprofile.config.Config config(Map<String,Object> values){
        return (org.eclipse.microprofile.config.Config)java.lang.reflect.Proxy.newProxyInstance(TenantRoutingTest.class.getClassLoader(),new Class<?>[]{org.eclipse.microprofile.config.Config.class},(proxy,method,args)->{
            if(method.getName().equals("getOptionalValue"))return Optional.ofNullable(values.get(args[0]));throw new UnsupportedOperationException();
        });
    }
    @Test void aSaturatedHttpTenantCannotConsumeTheOtherTenantSlot()throws Exception {
        var a=TenantId.create();var b=TenantId.create();var vertx=Vertx.vertx();var router=Router.router(vertx);
        var routing=new TenantRouting();routing.codec=new ContractCodec();routing.config=config(Map.of("toolgate.capacity.http-concurrency",1));
        routing.resolver=new TenantRequestResolver(Map.of("a.example.com",a,"b.example.com",b)::get);routing.routes(router);
        var held=new java.util.concurrent.CompletableFuture<io.vertx.ext.web.RoutingContext>();
        router.route().handler(ctx->{if(ctx.normalizedPath().equals("/held"))held.complete(ctx);else ctx.response().end("available");});
        var server=vertx.createHttpServer().requestHandler(router).listen(0,"127.0.0.1").toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);
        try(var socket=new java.net.Socket("127.0.0.1",server.actualPort())){
            socket.getOutputStream().write("GET /held HTTP/1.1\r\nHost: a.example.com\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var first=held.get(10,TimeUnit.SECONDS);
            var denied=raw(server.actualPort(),"a.example.com","/other","");
            assertTrue(denied.startsWith("HTTP/1.1 429"));assertTrue(denied.toLowerCase().contains("retry-after: 1"));
            assertTrue(raw(server.actualPort(),"b.example.com","/other","").endsWith("available"));
            var ended=new java.util.concurrent.CompletableFuture<Void>();vertx.runOnContext(ignored->first.response().end("done").onComplete(result->ended.complete(null)));ended.get(10,TimeUnit.SECONDS);
            socket.getInputStream().readAllBytes();
            assertTrue(raw(server.actualPort(),"a.example.com","/other","").endsWith("available"));
        }finally{server.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);vertx.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);}
    }
    @Test void realRouterBindsBeforeStaticAndSocketRoutesAndIgnoresForwardedHosts()throws Exception {
        var a=TenantId.create();var b=TenantId.create();var vertx=Vertx.vertx();
        var router=Router.router(vertx);var routing=new TenantRouting();routing.codec=new ContractCodec();routing.config=config();
        routing.resolver=new TenantRequestResolver(Map.of("a.example.com",a,"b.example.com",b)::get);routing.routes(router);
        router.route("/q/health/ready").order(-1000).handler(ctx->ctx.response().end("ready"));
        router.route().order(-900).handler(ctx->ctx.response().end(TenantRequest.require(ctx).tenant().value()));
        var server=vertx.createHttpServer().requestHandler(router).listen(0,"127.0.0.1").toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);
        try {
            assertTrue(raw(server.actualPort(),"a.example.com","/console/","").endsWith(a.value()));
            assertTrue(raw(server.actualPort(),"b.example.com","/api/control/v1/endpoint/socket","").endsWith(b.value()));
            var forwarded=raw(server.actualPort(),"a.example.com","/api/control/v1/users","X-Forwarded-Host: b.example.com\r\nForwarded: host=b.example.com\r\n");assertTrue(forwarded.endsWith(a.value()));
            var wrong=raw(server.actualPort(),"a.example.com","/console/","Toolgate-Tenant-ID: "+b.value()+"\r\n");assertTrue(wrong.startsWith("HTTP/1.1 403"));assertTrue(wrong.contains("TENANT_MISMATCH"));
            var unknown=raw(server.actualPort(),"unknown.example.com","/console/","");
            var suspended=raw(server.actualPort(),"suspended.example.com","/console/","");
            for(var reply:List.of(unknown,suspended)){assertTrue(reply.startsWith("HTTP/1.1 404"));assertTrue(reply.contains("TENANT_UNAVAILABLE"));assertTrue(reply.toLowerCase().contains("cache-control: no-store"));assertFalse(reply.contains("Retry-After"));}
            assertTrue(raw(server.actualPort(),"unknown.example.com","/q/health/ready","").endsWith("ready"));
        }finally{server.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);vertx.close().toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);}
    }
    private static String raw(int port,String host,String path,String headers)throws Exception {
        try(var socket=new java.net.Socket("127.0.0.1",port)){
            socket.setSoTimeout(10000);socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: "+host+"\r\n"+headers+"Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void browserOriginsMustBeCanonicalAndQuickstartExceptionIsLoopbackOnly(){
        var context=new TenantRequestResolver.Context(TenantId.create(),new TenantRequestResolver.Authority("a.example.com",443));
        var profile=new Store.RuntimeProfile("server","Tenant A","https://a.example.com","https://gateway.a.example.com");
        for(var origin:List.of("https://a.example.com","https://gateway.a.example.com"))TenantRouting.checkOrigin(context,profile,List.of(origin),true,false);
        TenantRouting.checkOrigin(context,null,List.of("https://a.example.com"),true,false);
        assertThrows(Failure.class,()->TenantRouting.checkOrigin(context,null,List.of("https://b.example.com"),true,false));
        for(var origin:List.of("null","https://b.example.com","http://a.example.com","https://a.example.com:8443","https://user@a.example.com","https://a.example.com/path","https://a.example.com?x","https://a.example.com#x"))assertEquals(ErrorCode.TENANT_MISMATCH,assertThrows(Failure.class,()->TenantRouting.checkOrigin(context,profile,List.of(origin),true,false)).code());
        assertThrows(Failure.class,()->TenantRouting.checkOrigin(context,profile,List.of("https://a.example.com","https://a.example.com"),true,false));
        var local=new TenantRequestResolver.Context(context.tenant(),new TenantRequestResolver.Authority("localhost",8080));
        TenantRouting.checkOrigin(local,profile,List.of("http://localhost:8080"),false,true);
        assertThrows(Failure.class,()->TenantRouting.checkOrigin(local,profile,List.of("http://localhost:8081"),false,true));
        assertThrows(Failure.class,()->TenantRouting.checkOrigin(local,profile,List.of("http://localhost:8080"),false,false));
    }
    @Test void aBackgroundFailureDoesNotSkipTheNextTenant(){
        var visits=new ArrayList<TenantId>();var a=TenantId.create();var b=TenantId.create();
        TenantWork.run(a,id->{visits.add(id);throw Failure.unavailable();});TenantWork.run(b,visits::add);assertEquals(List.of(a,b),visits);
    }
}
