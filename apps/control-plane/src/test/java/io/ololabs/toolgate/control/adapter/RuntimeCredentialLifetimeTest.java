// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCredentialLifetimeTest {
    final TenantId tenant=TenantId.create();
    VerifiedActors actors(long age,long remaining,TenantId tokenTenant,boolean role) {
        long now=System.currentTimeMillis()/1000;
        var actors=new VerifiedActors();actors.runtimeCredentialTtl=604800;
        actors.request=new TenantRequest(){@Override public TenantRequestResolver.Context context(){return new TenantRequestResolver.Context(tenant,new TenantRequestResolver.Authority("runtime.example.test",443));}};
        actors.jwt=(org.eclipse.microprofile.jwt.JsonWebToken)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.eclipse.microprofile.jwt.JsonWebToken.class},(proxy,method,args)->switch(method.getName()) {
            case "getClaim" -> "tenant_id".equals(args[0])?tokenTenant.value():null;
            case "getIssuer" -> "https://idp.example.test";
            case "getSubject" -> "runtime";
            case "getIssuedAtTime" -> now-age;
            case "getExpirationTime" -> now+remaining;
            default -> throw new AssertionError("Unexpected access");
        });
        actors.security=(io.quarkus.security.identity.SecurityIdentity)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{io.quarkus.security.identity.SecurityIdentity.class},(proxy,method,args)->switch(method.getName()) {
            case "isAnonymous" -> false;
            case "hasRole" -> role;
            default -> throw new AssertionError("Unexpected role access");
        });
        return actors;
    }
    @Test void olderRuntimeIdentityIsAcceptedOnlyAtTheDedicatedServiceBoundary() {
        var actor=actors(3600,600,tenant,true);
        assertEquals(tenant,actor.service("toolgate-workflow-runtime").tenant());
        assertEquals(401,assertThrows(Failure.class,actor::human).status());
        assertEquals(401,assertThrows(Failure.class,()->actor.service("toolgate-relay-gateway")).status());
        assertEquals(403,assertThrows(Failure.class,()->actors(3600,600,tenant,false).service("toolgate-workflow-runtime")).status());
    }
    @Test void expiredFutureOverlongAndForeignRuntimeClaimsAreRefused() {
        assertEquals(401,assertThrows(Failure.class,()->actors(3600,-1,tenant,true).service("toolgate-workflow-runtime")).status());
        assertEquals(401,assertThrows(Failure.class,()->actors(-30,600,tenant,true).service("toolgate-workflow-runtime")).status());
        assertEquals(401,assertThrows(Failure.class,()->actors(3600,604800,tenant,true).service("toolgate-workflow-runtime")).status());
        assertThrows(Failure.class,()->actors(3600,600,TenantId.create(),true).service("toolgate-workflow-runtime"));
    }
}
