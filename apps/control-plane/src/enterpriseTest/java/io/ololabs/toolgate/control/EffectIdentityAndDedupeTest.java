// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectIdentityAndDedupeTest {
    final ContractCodec codec=new ContractCodec();
    @Test void logicalIdentityIsStableAndEveryScopeComponentSeparatesEffects() {
        String effect=EffectIdentity.of(codec,"tenant","operation","route","charge");
        assertTrue(effect.matches("eff-[a-f0-9]{64}"));
        assertEquals(effect,EffectIdentity.of(codec,"tenant","operation","route","charge"));
        var ids=new HashSet<String>();ids.add(effect);
        ids.add(EffectIdentity.of(codec,"other","operation","route","charge"));
        ids.add(EffectIdentity.of(codec,"tenant","other","route","charge"));
        ids.add(EffectIdentity.of(codec,"tenant","operation","other","charge"));
        ids.add(EffectIdentity.of(codec,"tenant","operation","route","receipt"));
        assertEquals(5,ids.size());
        assertNotEquals(EffectIdentity.of(codec,"a","bc","r","k"),EffectIdentity.of(codec,"ab","c","r","k"));
        assertNotEquals(effect,EffectIdentity.of(codec,"tenant","operation","route","default"));
    }
    @Test void explicitServiceKeysHaveNoHistoricalAliases() {
        String route="olo_memory/memory.append";
        var ids=new HashSet<String>();
        for(String key:List.of("default","service-write","receipt"))
            ids.add(EffectIdentity.of(codec,"tenant","operation",route,key));
        assertEquals(3,ids.size());
        assertThrows(Failure.class,()->EffectIdentity.of(codec,"tenant","operation",route,null));
    }
    @Test void malformedUnboundedOrAmbiguousKeysCannotCreateAnIdentity() {
        for(String key:Arrays.asList(null,""," ","a/b","a\nb","é","a".repeat(129))) {
            assertThrows(Failure.class,()->EffectIdentity.of(codec,"t","o","r",key));
        }
        assertDoesNotThrow(()->EffectIdentity.of(codec,"t","o","r","a".repeat(128)));
    }
    @Test void retentionSubtractsConservativeMarginFromTheFirstAuthorization() {
        long first=1_000,day=86_400_000;
        assertEquals(first+77_760_000,TargetDedupePolicy.deadline(first,day,Long.MAX_VALUE/2048,Long.MAX_VALUE/2048).orElseThrow());
        assertEquals(first+77_760_000,TargetDedupePolicy.deadline(first,day+1,Long.MAX_VALUE/2048,Long.MAX_VALUE/2048).orElseThrow());
        assertEquals(first+3_600_000,TargetDedupePolicy.deadline(first,7_200_000,10_000_000,10_000_000).orElseThrow());
        assertTrue(TargetDedupePolicy.withinWindow(first+77_759_999,first,day,100_000_000,100_000_000));
        assertFalse(TargetDedupePolicy.withinWindow(first+77_760_000,first,day,100_000_000,100_000_000));
        assertFalse(TargetDedupePolicy.withinWindow(first-1,first,day,100_000_000,100_000_000));
    }
    @Test void reviewAndOperationDeadlinesNeverExtendOnRetry() {
        assertEquals(2000,TargetDedupePolicy.deadline(1000,86_400_000,2000,3000).orElseThrow());
        assertEquals(2000,TargetDedupePolicy.deadline(1000,86_400_000,3000,2000).orElseThrow());
        assertFalse(TargetDedupePolicy.withinWindow(2000,1000,86_400_000,2000,3000));
        assertFalse(TargetDedupePolicy.withinWindow(2000,1000,86_400_000,3000,2000));
    }
    @Test void absentInvalidTinyAndUnrepresentableWindowsFailClosed() {
        for(long retention:new long[]{Long.MIN_VALUE,-1,0,1,3_600_000,Long.MAX_VALUE}) {
            assertTrue(TargetDedupePolicy.deadline(1000,retention,100_000_000,100_000_000).isEmpty());
        }
        assertTrue(TargetDedupePolicy.deadline(-1,86_400_000,100_000_000,100_000_000).isEmpty());
        assertTrue(TargetDedupePolicy.deadline(1000,86_400_000,1000,100_000_000).isEmpty());
        assertTrue(TargetDedupePolicy.deadline(1000,86_400_000,100_000_000,1000).isEmpty());
        assertTrue(TargetDedupePolicy.deadline(Long.MAX_VALUE,86_400_000,Long.MAX_VALUE,Long.MAX_VALUE).isEmpty());
    }
}
