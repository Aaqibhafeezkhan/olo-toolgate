// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuickstartReadinessProxyTest {
    QuickstartProxy proxy(boolean enabled,boolean tls,String host,String origin) {
        var proxy=new QuickstartProxy();
        proxy.config=(org.eclipse.microprofile.config.Config)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.eclipse.microprofile.config.Config.class},
            (p,m,args)->m.getName().equals("getOptionalValue")?Optional.of(enabled):null);
        var request=(io.vertx.core.http.HttpServerRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{io.vertx.core.http.HttpServerRequest.class},(p,m,args)->switch(m.getName()) {
            case "isSSL" -> tls;
            case "getHeader" -> args[0].toString().equals("Host")?host:origin;
            default -> throw new AssertionError("Refuse before relay");
        });
        proxy.routing=(io.vertx.ext.web.RoutingContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{io.vertx.ext.web.RoutingContext.class},(p,m,args)-> {
            if(m.getName().equals("request"))return request;throw new AssertionError("Refuse before relay");
        });
        return proxy;
    }
    @Test void readinessProxyIsClosedOutsideQuickstartOrTrustedLocalTlsOrigin() {
        assertEquals(404,assertThrows(Failure.class,()->proxy(false,true,"localhost",null).workflowGatewayReadiness()).status());
        assertEquals(401,assertThrows(Failure.class,()->proxy(true,false,"localhost",null).workflowGatewayReadiness()).status());
        assertEquals(403,assertThrows(Failure.class,()->proxy(true,true,"foreign.example",null).workflowGatewayReadiness()).status());
        assertEquals(403,assertThrows(Failure.class,()->proxy(true,true,"localhost","https://foreign.example").workflowGatewayReadiness()).status());
    }
}
