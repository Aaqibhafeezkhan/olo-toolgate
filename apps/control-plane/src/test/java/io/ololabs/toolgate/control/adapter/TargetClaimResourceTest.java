// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.*;
import java.security.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real verified-principal/role checks at each resource method; no datastore is available to a refused caller. */
class TargetClaimResourceTest {
    static java.security.interfaces.RSAPrivateCrtKey key;
    final TenantId tenant=TenantId.create();
    @BeforeAll static void key()throws Exception{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=(java.security.interfaces.RSAPrivateCrtKey)g.generateKeyPair().getPrivate();}
    TargetClaimResource resource(Set<String> roles,boolean anonymous,TenantId jwtTenant){
        long now=System.currentTimeMillis()/1000;
        var actors=new VerifiedActors();
        actors.request=new TenantRequest(){@Override public TenantRequestResolver.Context context(){return new TenantRequestResolver.Context(tenant,new TenantRequestResolver.Authority("target.example.test",443));}};
        actors.jwt=(org.eclipse.microprofile.jwt.JsonWebToken)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{org.eclipse.microprofile.jwt.JsonWebToken.class},(proxy,method,args)->switch(method.getName()){
                case "getClaim" -> "tenant_id".equals(args[0])?jwtTenant.value():null;
                case "getIssuer" -> "https://idp.example.test";
                case "getSubject" -> "target-service";
                case "getIssuedAtTime" -> now-1;
                case "getExpirationTime" -> now+300;
                default -> throw new AssertionError("Unexpected identity access");
            });
        actors.security=(io.quarkus.security.identity.SecurityIdentity)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{io.quarkus.security.identity.SecurityIdentity.class},(proxy,method,args)->switch(method.getName()){
                case "isAnonymous" -> anonymous;
                case "hasRole" -> roles.contains((String)args[0]);
                default -> throw new AssertionError("Unexpected role access");
            });
        var result=new TargetClaimResource();result.actors=actors;result.codec=new ContractCodec();
        result.operations=new EnterpriseOperations(null,result.codec,new RsaEffectSigner(key,"effect-key",result.codec),
            java.time.Clock.systemUTC(),"control","toolgate-client",60000,100);
        return result;
    }
    @Test void onlyTheDedicatedCurrentTargetRoleCanReadItsPublicVerificationMaterial(){
        var resource=resource(Set.of("toolgate-target-billing"),false,tenant);
        try(var response=resource.keys("billing")){
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeaderString("Cache-Control"));
            String body=(String)response.getEntity();assertTrue(body.contains("effect-key"));assertFalse(body.contains("PRIVATE"));
        }
        assertEquals(403,assertThrows(Failure.class,()->resource.keys("other")).status());
    }
    @Test void unrelatedServiceAndHumanRolesCannotReachTargetStateOrParseSubmittedBodies(){
        for(var roles:List.of(Set.of("toolgate-admin"),Set.of("toolgate-service-olo-memory"),Set.of("toolgate-target-other"))){
            var resource=resource(roles,false,tenant);
            assertEquals(403,assertThrows(Failure.class,()->resource.keys("billing")).status());
            assertEquals(403,assertThrows(Failure.class,()->resource.consume("billing","not JSON")).status());
            assertEquals(403,assertThrows(Failure.class,()->resource.status("billing","not JSON")).status());
            assertEquals(403,assertThrows(Failure.class,()->resource.outcome("billing","not JSON")).status());
        }
    }
    @Test void anonymousForeignTenantAndMalformedTargetRequestsAreRejectedBeforeDatastoreAccess(){
        var anonymous=resource(Set.of("toolgate-target-billing"),true,tenant);
        assertEquals(401,assertThrows(Failure.class,()->anonymous.keys("billing")).status());
        var foreign=resource(Set.of("toolgate-target-billing"),false,TenantId.create());
        assertEquals(403,assertThrows(Failure.class,()->foreign.keys("billing")).status());
        var current=resource(Set.of("toolgate-target-billing"),false,tenant);
        for(var target:List.of("../billing","Billing","billing/other",""))
            assertEquals(403,assertThrows(Failure.class,()->current.keys(target)).status());
    }
}
