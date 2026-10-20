// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TenantRequestResolverTest {
    @Test void strictAuthorityParsingRejectsAmbiguousAndRepeatedHeaders() {
        assertEquals(new TenantRequestResolver.Authority("tenant.example.com",443),TenantRequestResolver.authority(List.of("TENANT.example.com."),443));
        assertEquals(new TenantRequestResolver.Authority("0:0:0:0:0:0:0:1",8443),TenantRequestResolver.authority(List.of("[::1]:8443"),443));
        for(String value:List.of("","tenant.example.com:","tenant.example.com:0","tenant.example.com:65536","tenant.example.com:0443","tenant.example.com:443:443","tenant.example.com,evil.example","user@tenant.example.com","https://tenant.example.com","tenant.example.com/path","tenant.example.com?x","tenant.example.com#x"," tenant.example.com","tenant.example.com\r\n","127.1","0127.0.0.1","::1","[localhost]","[::1]suffix","tenant.example.com%2eevil"))
            assertEquals(400,assertThrows(Failure.class,()->TenantRequestResolver.authority(List.of(value),443)).status(),value);
        assertThrows(Failure.class,()->TenantRequestResolver.authority(List.of("tenant.example.com","tenant.example.com"),443));
    }
    @Test void headerIsOptionalAndPrincipalMustMatchBeforeAnyTenantWork() {
        var a=TenantId.create();var b=TenantId.create();var hosts=Map.of("a.example.com",a,"b.example.com",b);var resolver=new TenantRequestResolver(hosts::get);
        var context=resolver.resolve(List.of("a.example.com:8443"),443,"a.example.com",List.of());
        assertEquals(a,context.tenant());TenantRequestResolver.principal(context,a.value());
        assertEquals(403,assertThrows(Failure.class,()->TenantRequestResolver.principal(context,b.value())).status());
        assertEquals(401,assertThrows(Failure.class,()->TenantRequestResolver.principal(context,null)).status());
        assertEquals(403,assertThrows(Failure.class,()->resolver.resolve(List.of("a.example.com"),443,null,List.of(b.value()))).status());
        var malformed=assertThrows(Failure.class,()->resolver.resolve(List.of("a.example.com"),443,null,List.of("default")));
        assertEquals(400,malformed.status());assertEquals(io.ololabs.toolgate.contracts.ErrorCode.INVALID_TENANT_ID,malformed.code());
        assertEquals(400,assertThrows(Failure.class,()->resolver.resolve(List.of("a.example.com"),443,null,List.of(a.value(),a.value()))).status());
        assertEquals(421,assertThrows(Failure.class,()->resolver.resolve(List.of("b.example.com"),443,"a.example.com",List.of())).status());
        assertEquals(404,assertThrows(Failure.class,()->resolver.resolve(List.of("unknown.example.com"),443,null,List.of(a.value()))).status());
    }
}
