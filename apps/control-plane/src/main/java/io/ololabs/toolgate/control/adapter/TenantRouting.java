// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.*;
import javax.net.ssl.*;
import org.eclipse.microprofile.config.Config;

/** Runs before static assets, authentication-side writes and WebSocket upgrades. No forwarded authority is trusted. */
@ApplicationScoped
public class TenantRouting {
    @Inject TenantRequestResolver resolver;
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject Config config;
    static boolean tenantPath(String path){return !path.equals("/q")&&!path.startsWith("/q/");}
    void routes(@Observes Router router){
        if(config.getOptionalValue("quarkus.http.proxy.proxy-address-forwarding",Boolean.class).orElse(false))
            throw new IllegalStateException("Tenant routing requires direct authority; forwarded address rewriting is unsupported");
        router.route().order(-2000).handler(context->{
            if(!tenantPath(context.normalizedPath())){context.next();return;}
            // Capture transport inputs on the event loop. Database work runs on the worker pool.
            try {
                var request=context.request();var hosts=new ArrayList<>(request.headers().getAll("Host"));
                if(request.version()==io.vertx.core.http.HttpVersion.HTTP_2&&request.authority()!=null){
                    var authority=request.authority();String host=authority.host();
                    String value=(host.contains(":")?"["+host+"]":host)+(authority.port()>0?":"+authority.port():"");
                    if(!hosts.isEmpty()&&!TenantRequestResolver.authority(hosts,request.isSSL()?443:80).equals(TenantRequestResolver.authority(List.of(value),request.isSSL()?443:80)))throw TenantRequestResolver.misdirected();
                    hosts.clear();hosts.add(value);
                }
                var expected=List.copyOf(request.headers().getAll("Toolgate-Tenant-ID"));
                var origins=List.copyOf(request.headers().getAll("Origin"));
                String sni=sni(request.sslSession());boolean ssl=request.isSSL();
                context.vertx().executeBlocking(()->{
                    var bound=resolver.resolve(hosts,ssl?443:80,sni,expected);
                    if(!origins.isEmpty()){
                        var profile=store.transaction(bound.tenant(),false,Store.Session::runtimeProfile);
                        checkOrigin(bound,profile,origins,ssl,config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false));
                    }
                    return bound;
                }).onComplete(result->{
                    if(result.failed()){reject(context,result.cause());return;}
                    context.put(TenantRequest.KEY,result.result());context.next();
                });
            }catch(RuntimeException invalid){reject(context,invalid);}
        });
    }
    static String sni(SSLSession session){
        if(!(session instanceof ExtendedSSLSession extended))return null;
        var names=extended.getRequestedServerNames();if(names==null||names.isEmpty())return null;
        String host=null;for(var name:names)if(name instanceof SNIHostName dns){if(host!=null)throw Failure.validation();host=dns.getAsciiName();}return host;
    }
    static void checkOrigin(TenantRequestResolver.Context context,Store.RuntimeProfile profile,List<String> values,boolean ssl,boolean quickstart){
        if(values.size()!=1)throw TenantRequestResolver.mismatch();
        String value=values.getFirst();
        try {
            var uri=java.net.URI.create(value);
            if(uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().isEmpty())throw TenantRequestResolver.mismatch();
            var origin=TenantRequestResolver.authority(List.of(uri.getRawAuthority()),"https".equals(uri.getScheme())?443:80);
            boolean approved=profile==null?ssl&&"https".equals(uri.getScheme())&&origin.equals(context.authority()):List.of(profile.controlUrl(),profile.gatewayUrl()).stream().anyMatch(candidate->{
                var canonical=java.net.URI.create(candidate);return "https".equals(uri.getScheme())&&origin.equals(TenantRequestResolver.authority(List.of(canonical.getRawAuthority()),443));
            });
            boolean local=quickstart&&!ssl&&"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","0:0:0:0:0:0:0:1").contains(origin.host())&&origin.equals(context.authority());
            if(!approved&&!local)throw TenantRequestResolver.mismatch();
        }catch(IllegalArgumentException|NullPointerException invalid){throw TenantRequestResolver.mismatch();}
    }
    private void reject(RoutingContext context,Throwable error){
        var failure=error instanceof Failure known?known:Failure.unavailable();
        context.response().setStatusCode(failure.status()).putHeader("Content-Type","application/json")
            .putHeader("Cache-Control","no-store").end(codec.json(new ErrorEnvelope(failure.code(),UUID.randomUUID().toString(),failure.status()==503)));
    }
}
