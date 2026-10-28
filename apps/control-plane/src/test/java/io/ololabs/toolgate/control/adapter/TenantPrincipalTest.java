// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TenantPrincipalTest {
    @Test void wrongTenantJwtCannotReachIdentityObservationOrServiceRoleChecks(){
        var requestTenant=TenantId.create();var tokenTenant=TenantId.create();long now=System.currentTimeMillis()/1000;
        var actors=new VerifiedActors();actors.request=new TenantRequest(){@Override public TenantRequestResolver.Context context(){return new TenantRequestResolver.Context(requestTenant,new TenantRequestResolver.Authority("a.example.com",443));}};
        actors.jwt=(org.eclipse.microprofile.jwt.JsonWebToken)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.eclipse.microprofile.jwt.JsonWebToken.class},(proxy,method,args)->switch(method.getName()){
            case "getClaim" -> "tenant_id".equals(args[0])?tokenTenant.value():null;
            case "getIssuer" -> "https://idp.example.com";
            case "getSubject" -> "subject";
            case "getIssuedAtTime" -> now-1;
            case "getExpirationTime" -> now+300;
            default -> throw new AssertionError("Identity must be rejected before claims/data access: "+method.getName());
        });
        actors.security=(io.quarkus.security.identity.SecurityIdentity)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{io.quarkus.security.identity.SecurityIdentity.class},(proxy,method,args)->{
            if(method.getName().equals("isAnonymous"))return false;throw new AssertionError("Tenant mismatch must precede roles and identity writes");
        });
        // Store and DirectoryService intentionally absent: touching either makes this test fail.
        assertEquals(ErrorCode.TENANT_MISMATCH,assertThrows(Failure.class,actors::human).code());
        assertEquals(ErrorCode.TENANT_MISMATCH,assertThrows(Failure.class,()->actors.service("toolgate-relay-gateway")).code());
    }
}
