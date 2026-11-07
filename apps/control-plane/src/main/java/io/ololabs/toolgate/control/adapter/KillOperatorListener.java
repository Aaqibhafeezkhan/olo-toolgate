// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.v2.KillTrustKey;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.*;
import io.vertx.ext.web.*;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A separately bound operator-CA listener. It can add kills, never recover or authorize execution. */
@ApplicationScoped
public class KillOperatorListener {
    private static final Logger AUDIT=Logger.getLogger("toolgate.audit.kill-operator");
    @Inject Vertx vertx;@Inject Config config;@Inject ContractCodec codec;
    @Inject PostgresStore store;@Inject EnterpriseOperations operations;@Inject FleetBootstrap fleet;
    @Inject IdentityKeyBootstrap identity;
    private HttpServer server;private WorkerExecutor workers;
    private final Semaphore slots=new Semaphore(32);
    private Ids.TenantId tenant;private OperatorApprovals approvals;private KillSwitch kills;
    private String setting(String name){return config.getValue("toolgate.control.breakglass."+name,String.class);}
    void start(@Observes StartupEvent ignored){
        var port=config.getOptionalValue("toolgate.control.breakglass.port",Integer.class);
        if(port.isEmpty())return;
        try{
            var keys=codec.configuration(ProtectedCustody.text(Path.of(setting("operator-keys-path")),16384),false);
            if(!keys.isArray()||keys.size()!=3)throw new IllegalArgumentException();
            var publicKeys=new ArrayList<KillTrustKey>();
            for(var key:keys)publicKeys.add(codec.model(codec.json(key),KillTrustKey.class));
            var verifier=new RsaOperatorApprovals(codec,publicKeys,identity.nonOperatorModuli());
            identity.optional();
            fleet.killCrypto();
            var deviceCaPath=config.getOptionalValue("toolgate.control.endpoint.ca-certificate-path",String.class);
            if(deviceCaPath.isPresent()){
                var factory=java.security.cert.CertificateFactory.getInstance("X.509");
                var operatorCa=(X509Certificate)factory.generateCertificate(new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(setting("client-ca-path")),32768)));
                var deviceCa=(X509Certificate)factory.generateCertificate(new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(deviceCaPath.get()),32768)));
                if(java.util.Arrays.equals(operatorCa.getPublicKey().getEncoded(),deviceCa.getPublicKey().getEncoded()))throw new IllegalArgumentException();
            }
            listen(config.getOptionalValue("toolgate.control.breakglass.host",String.class).orElse("127.0.0.1"),port.get(),
                setting("certificate-path"),setting("private-key-path"),setting("client-ca-path"),
                new Ids.TenantId(setting("tenant-id")),verifier,new KillSwitch(store,codec,operations,()->fleet.killCrypto()));
        }catch(Exception invalid){throw new IllegalStateException("Dedicated operator TLS custody and disjoint 2-of-3 keys required");}
    }
    void listen(String host,int port,String certificate,String key,String ca,Ids.TenantId tenant,OperatorApprovals approvals,KillSwitch kills){
        this.tenant=tenant;this.approvals=approvals;this.kills=kills;
        try{
            var options=new HttpServerOptions().setHost(host).setPort(port).setSsl(true).setClientAuth(ClientAuth.REQUIRED)
                .setKeyCertOptions(new PemKeyCertOptions().setCertValue(Buffer.buffer(ProtectedCustody.read(Path.of(certificate),32768)))
                    .setKeyValue(Buffer.buffer(ProtectedCustody.privateKey(Path.of(key),16384))))
                .setTrustOptions(new PemTrustOptions().addCertValue(Buffer.buffer(ProtectedCustody.read(Path.of(ca),32768))))
                .setUseAlpn(false).setEnabledSecureTransportProtocols(Set.of("TLSv1.3","TLSv1.2"))
                .setSslHandshakeTimeout(5).setSslHandshakeTimeoutUnit(TimeUnit.SECONDS)
                .setMaxHeaderSize(8192).setMaxInitialLineLength(2048).setIdleTimeout(5);
            workers=vertx.createSharedWorkerExecutor("toolgate-kill-operators",16);
            server=vertx.createHttpServer(options).exceptionHandler(error->AUDIT.info("operator_kill_transport_refused")).requestHandler(router()).listen()
                .toCompletionStage().toCompletableFuture().get(30,TimeUnit.SECONDS);
        }catch(Exception failure){stop();throw new IllegalStateException("Operator kill listener could not start",failure);}
    }
    int port(){return server==null?-1:server.actualPort();}
    private static String peer(RoutingContext ctx){
        try{
            if(!ctx.request().isSSL())return null;
            var chain=ctx.request().connection().peerCertificates();
            if(chain.isEmpty()||!(chain.get(0) instanceof X509Certificate certificate))return null;
            certificate.checkValidity();
            if(certificate.getBasicConstraints()!=-1||!List.of("1.3.6.1.5.5.7.3.2").equals(certificate.getExtendedKeyUsage()))return null;
            return DirectoryService.digest(certificate.getEncoded());
        }catch(Exception invalid){return null;}
    }
    Router router(){
        var router=Router.router(vertx);
        router.route().handler(ctx->{
            String peer=peer(ctx);
            if(peer==null){ctx.response().setStatusCode(401).end();AUDIT.info("operator_kill_refused status=401");return;}
            if(!slots.tryAcquire()){ctx.response().setStatusCode(503).end();AUDIT.info("operator_kill_refused status=503");return;}
            var processing=new AtomicBoolean();var released=new AtomicBoolean();
            Runnable release=()->{if(released.compareAndSet(false,true))slots.release();};
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            long timer=vertx.setTimer(5000,id->{ctx.request().connection().close();if(!processing.get())release.run();});
            ctx.put("operator",peer).put("deadline",deadline).put("processing",processing).put("release",release);
            ctx.addEndHandler(result->{vertx.cancelTimer(timer);if(!processing.get())release.run();});
            ctx.next();
        });
        router.post("/break-glass/kills").handler(BodyHandler.create().setBodyLimit(131072));
        router.post("/break-glass/kills").handler(ctx->{
            var processing=(AtomicBoolean)ctx.get("processing");processing.set(true);
            workers.<Store.Reply>executeBlocking(()->{
                if(System.nanoTime()>(long)ctx.get("deadline"))throw Failure.unavailable();
                if(ctx.request().headers().getAll("Content-Type").size()!=1||ctx.request().headers().getAll("Idempotency-Key").size()!=1
                    ||ctx.request().headers().getAll("Toolgate-Tenant-ID").size()>1)throw Failure.validation();
                String owner=ctx.request().getHeader("Toolgate-Tenant-ID");
                if(owner!=null&&!owner.equals(tenant.value()))throw ManagementAccess.denied();
                String content=ctx.request().getHeader("Content-Type");
                if(content==null||!content.split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw Failure.validation();
                return kills.breakGlass(tenant,ctx.get("operator"),ctx.request().getHeader("Idempotency-Key"),ctx.body().asString(),approvals);
            },false).onComplete(result->{
                int status;String body;
                if(result.succeeded()){status=result.result().status();body=result.result().body();}
                else if(result.cause() instanceof Failure failure){status=failure.status();body=codec.json(Map.of("code",failure.code()));}
                else if(result.cause() instanceof IllegalArgumentException||result.cause() instanceof NullPointerException){status=400;body=codec.json(Map.of("code","VALIDATION"));}
                else{status=503;body=codec.json(Map.of("code","DEPENDENCY_UNAVAILABLE"));}
                AUDIT.infof("operator_kill_access certificate=%s tenant=%s status=%d",ctx.get("operator"),tenant.value(),status);
                try{ctx.response().setStatusCode(status).putHeader("Content-Type","application/json").putHeader("Cache-Control","no-store").end(body);}
                finally{((Runnable)ctx.get("release")).run();}
            });
        });
        router.route().handler(ctx->{
            AUDIT.infof("operator_kill_refused certificate=%s tenant=%s status=404",ctx.get("operator"),tenant.value());
            ctx.response().setStatusCode(404).end();
        });
        router.route().failureHandler(ctx->{
            int status=ctx.statusCode()>=400?ctx.statusCode():400;
            AUDIT.infof("operator_kill_refused certificate=%s tenant=%s status=%d",ctx.get("operator"),tenant.value(),status);
            ctx.response().setStatusCode(status).putHeader("Content-Type","application/json").putHeader("Cache-Control","no-store")
                .end(codec.json(Map.of("code",status==413?"REQUEST_TOO_LARGE":"VALIDATION")));
        });
        return router;
    }
    @PreDestroy void stop(){if(server!=null)server.close();if(workers!=null)workers.close();}
}
