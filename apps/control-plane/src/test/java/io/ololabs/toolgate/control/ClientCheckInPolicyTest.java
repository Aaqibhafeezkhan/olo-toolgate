// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import static org.junit.jupiter.api.Assertions.*;

import io.ololabs.toolgate.contracts.v2.ValueSource;
import io.ololabs.toolgate.control.application.ClientCheckInPolicy;
import org.junit.jupiter.api.Test;

class ClientCheckInPolicyTest {
    @Test void unsetValuesUseTheEnterpriseDefaultsAndDeriveTheOfflineThreshold(){
        var defaults=ClientCheckInPolicy.configure(null,null,null);
        assertEquals(new ClientCheckInPolicy(2000,20,120000,ValueSource.DEFAULT),defaults);
        var slow=ClientCheckInPolicy.configure(20000L,0,null);
        assertEquals(ValueSource.ENVIRONMENT,slow.source());
        assertEquals(120000,slow.offlineAfterMs());
        assertEquals(new ClientCheckInPolicy(5000,0,300000,ValueSource.ENVIRONMENT),ClientCheckInPolicy.configure(5000L,0,300000L));
        var effective=ClientCheckInPolicy.QUICKSTART.effective();
        assertEquals(2000L,effective.checkInMs());assertEquals(0L,effective.jitterPct());assertEquals(120000L,effective.offlineAfterMs());
    }
    @Test void theCheckInMustLeaveTimeForAJobInsideTheSyncDeadline(){
        // 20000 ms with 25 % jitter is exactly 25 s, which leaves the 5 s execution margin.
        assertDoesNotThrow(()->ClientCheckInPolicy.configure(20000L,25,null));
        assertThrows(IllegalArgumentException.class,()->ClientCheckInPolicy.configure(20001L,25,null));
        assertThrows(IllegalArgumentException.class,()->ClientCheckInPolicy.configure(499L,0,null));
        assertThrows(IllegalArgumentException.class,()->ClientCheckInPolicy.configure(2000L,51,null));
        // The offline threshold must exceed the longest interval a healthy client can wait.
        assertThrows(IllegalArgumentException.class,()->ClientCheckInPolicy.configure(2000L,20,2400L));
        assertDoesNotThrow(()->ClientCheckInPolicy.configure(2000L,20,2401L));
    }
    @Test void intervalsStayInsideTheJitterBandAndTheBurstGuardScales(){
        assertEquals(1000,ClientCheckInPolicy.QUICKSTART.burstGuardMs());
        assertEquals(2000,ClientCheckInPolicy.QUICKSTART.nextIntervalMs());
        var enterprise=ClientCheckInPolicy.configure(null,null,null);
        assertEquals(800,enterprise.burstGuardMs());
        for(int i=0;i<500;i++){long next=enterprise.nextIntervalMs();assertTrue(next>=1600&&next<=2400,String.valueOf(next));}
        assertEquals(2,enterprise.nextIntervalSeconds(1600));
        assertEquals(3,enterprise.nextIntervalSeconds(2400));
        assertEquals(2,enterprise.nextIntervalSeconds(500));
    }
    @Test void aDeviceIsOfflineAfterTheConfiguredThreshold(){
        var policy=ClientCheckInPolicy.configure(1000L,0,10000L);
        assertTrue(policy.offline(0,50000));
        assertFalse(policy.offline(40000,50000));
        assertTrue(policy.offline(39999,50000));
    }
}
