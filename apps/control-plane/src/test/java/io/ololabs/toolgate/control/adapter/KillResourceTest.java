// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Proxy;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Resource refusal tests; these do not replace actual accepted-TLS qualification. */
class KillResourceTest {
    KillResource resource(boolean tls){
        var session=(SSLSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SSLSession.class},(proxy,method,args)->{
            if(method.getName().equals("getPeerCertificates"))throw new javax.net.ssl.SSLPeerUnverifiedException("No verified peer");
            return null;
        });
        var request=(HttpServerRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServerRequest.class},(proxy,method,args)->switch(method.getName()){
            case "isSSL"->tls;
            case "sslSession"->session;
            case "getHeader"->throw new AssertionError("Identity headers must not be consulted");
            default->null;
        });
        var resource=new KillResource();
        resource.routing=(RoutingContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoutingContext.class},
            (proxy,method,args)->method.getName().equals("request")?request:null);
        return resource;
    }
    @Test void plaintextAndForwardedIdentityCannotReachKillDeliveryOrAcknowledgements(){
        var resource=resource(false);
        assertEquals(401,assertThrows(Failure.class,resource::nativeState).status());
        assertEquals(401,assertThrows(Failure.class,resource::nativeKeys).status());
        assertEquals(401,assertThrows(Failure.class,()->resource.nativeAcknowledge("{}")).status());
        assertEquals(401,assertThrows(Failure.class,resource::hostState).status());
        assertEquals(401,assertThrows(Failure.class,resource::hostKeys).status());
        assertEquals(401,assertThrows(Failure.class,()->resource.poll(0,0)).status());
        assertEquals(401,assertThrows(Failure.class,()->resource.acknowledge("{}")).status());
    }
    @Test void tlsWithoutAnAcceptedClientCertificateCannotUseBearerOrIdentityHeadersInstead(){
        var resource=resource(true);
        assertEquals(401,assertThrows(Failure.class,resource::nativeState).status());
        assertEquals(401,assertThrows(Failure.class,resource::nativeKeys).status());
        assertEquals(401,assertThrows(Failure.class,()->resource.nativeAcknowledge("{}")).status());
        assertEquals(401,assertThrows(Failure.class,resource::hostState).status());
        assertEquals(401,assertThrows(Failure.class,resource::hostKeys).status());
        assertEquals(401,assertThrows(Failure.class,()->resource.poll(0,0)).status());
        assertEquals(401,assertThrows(Failure.class,()->resource.acknowledge("{}")).status());
    }
}
