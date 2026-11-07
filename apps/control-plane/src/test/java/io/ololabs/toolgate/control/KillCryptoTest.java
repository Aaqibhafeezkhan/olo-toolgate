// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KillCryptoTest {
    final ContractCodec codec=new ContractCodec();
    KillCrypto crypto()throws Exception{return (KillCrypto)new FleetTest().crypto();}
    ObjectNode event(){return (ObjectNode)codec.value("{\"eventId\":\"control:1\",\"tenantId\":\"10000000-0000-4000-8000-000000000001\",\"authorizationEpoch\":7,\"reason\":\"Emergency\"}");}
    @Test void canonicalEventsBindTenantEpochAndSeparateEverySignatureDomain()throws Exception{
        var signer=crypto();var event=event();String signed=signer.signEvent(event);
        assertEquals(event,signer.verifyEvent(signed));
        assertThrows(Failure.class,()->signer.verifyPush(signed));
        assertThrows(Failure.class,()->crypto().verifyEvent(signed));
        var changed=event.deepCopy().put("tenantId","20000000-0000-4000-8000-000000000002");
        String[] parts=signed.split("\\.");
        parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString(Canonical.jcs(changed));
        assertThrows(Failure.class,()->signer.verifyEvent(String.join(".",parts)));
        var oldEpoch=event.deepCopy().put("authorizationEpoch",6);
        assertNotEquals(signed,signer.signEvent(oldEpoch));
        assertThrows(Failure.class,()->signer.signEvent(event.deepCopy().put("signature",signed)));
        assertThrows(Failure.class,()->signer.signEvent(event.deepCopy().put("reason","x".repeat(24001))));
        assertThrows(Failure.class,()->signer.verifyEvent("e30.e30.c2ln"));
        assertThrows(Failure.class,()->signer.verifyEvent("x".repeat(32769)));
        assertThrows(Failure.class,()->signer.verifyEvent(null));
    }
    @Test void boundedPushSupportsCurrentCompleteStateWithoutBecomingAnEvent()throws Exception{
        var signer=crypto();var push=event().put("reason","x".repeat(40000));
        String signed=signer.signPush(push);assertEquals(push,signer.verifyPush(signed));
        assertThrows(Failure.class,()->signer.verifyEvent(signed));
        assertThrows(Failure.class,()->signer.signPush(push.deepCopy().put("signature",signed)));
        assertThrows(Failure.class,()->signer.signPush(push.deepCopy().put("reason","x".repeat(1500001))));
        assertThrows(Failure.class,()->signer.verifyPush("x".repeat(2097153)));
    }
}
