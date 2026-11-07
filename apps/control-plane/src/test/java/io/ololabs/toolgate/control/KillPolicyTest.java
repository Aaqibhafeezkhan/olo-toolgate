// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KillPolicyTest {
    final KillPolicy.Subject subject=new KillPolicy.Subject("acme.orders","sha256:"+"a".repeat(64),"acme","acme.orders/pay","payments","sha256:"+"b".repeat(64));
    @Test void everyDeclaredScopeUsesItsOwnPinnedFact(){
        var values=List.of("acme.orders","sha256:"+"a".repeat(64),"acme","acme.orders/pay","payments","sha256:"+"b".repeat(64));
        int index=0;
        for(var kind:KillScopeKind.values()){
            var scope=new KillScope(kind,kind==KillScopeKind.TENANT?null:values.get(index++));
            assertTrue(KillPolicy.matches(scope,subject));
            assertTrue(KillPolicy.blocks(KillMode.STOP_NEW,scope,subject,false));
            assertFalse(KillPolicy.blocks(KillMode.STOP_NEW,scope,subject,true));
            assertTrue(KillPolicy.blocks(KillMode.TERMINATE,scope,subject,true));
            if(kind!=KillScopeKind.TENANT){
                String other=kind==KillScopeKind.PACKAGE_DIGEST||kind==KillScopeKind.RUNTIME_IMAGE?"sha256:"+"c".repeat(64):"other";
                assertFalse(KillPolicy.matches(new KillScope(kind,other),subject));
            }
        }
    }
    @Test void unknownAuthorityCannotProveNonMatchingButKnownNonApplicableScopeCan(){
        var unknown=new KillPolicy.Subject(null,null,null,"tool",null,null);
        for(var kind:KillScopeKind.values()){
            String value=kind==KillScopeKind.TENANT?null:kind==KillScopeKind.PACKAGE_DIGEST||kind==KillScopeKind.RUNTIME_IMAGE?"sha256:"+"a".repeat(64):"x";
            assertEquals(kind!=KillScopeKind.TOOL,KillPolicy.matches(new KillScope(kind,value),unknown));
        }
        var nativeSubject=new KillPolicy.Subject("acme","sha256:"+"a".repeat(64),"acme","tool","","");
        assertFalse(KillPolicy.matches(new KillScope(KillScopeKind.POOL,"pool"),nativeSubject));
        assertFalse(KillPolicy.matches(new KillScope(KillScopeKind.RUNTIME_IMAGE,"sha256:"+"a".repeat(64)),nativeSubject));
    }
    @Test void malformedOrAmbiguousScopeCannotBeSilentlyIgnored(){
        for(var scope:List.of(new KillScope(KillScopeKind.TENANT,"unexpected"),
                new KillScope(KillScopeKind.TOOL,null),new KillScope(KillScopeKind.PACKAGE_DIGEST,"a".repeat(64)),
                new KillScope(KillScopeKind.RUNTIME_IMAGE,"sha256:"+"A".repeat(64)),new KillScope(KillScopeKind.POOL,"../pool?x"))){
            assertThrows(Failure.class,()->KillPolicy.validate(scope));
        }
        assertThrows(Failure.class,()->KillPolicy.validate(null));
    }
}
