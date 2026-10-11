// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.CredentialKind;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The request context names no device; Control places each call on one member of the binding's device group. */
class DevicePlacementTest {
    @TempDir Path temp;EnterpriseConformanceTest graph;PostgresStore store;DevicePlacement placement;
    @BeforeEach void setup(){graph=new EnterpriseConformanceTest();graph.graph();placement=new DevicePlacement(graph.codec,ClientCheckInPolicy.configure(null,null,null));}
    void pool(Map<String,String> owners){
        owners.forEach((id,owner)->graph.add(Kind.DEVICE,new ControlDevice(id,id,true,1L,owner)));
        graph.add(Kind.DEVICE_GROUP,new ControlDeviceGroup("devices-a","Devices A",true,2L,List.copyOf(new TreeSet<>(owners.keySet()))));
        store=new PostgresStore(SqliteState.open(temp.resolve("placement.sqlite")),graph.codec);
        store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),graph.directory());return null;});
    }
    RequestContext context(String request,String user){return new RequestContext(request,EnterpriseConformanceTest.TENANT.value(),user==null?RequestMode.SERVICE:RequestMode.DELEGATED,CredentialKind.STATIC_TOKEN,null,user,"agent","workload",List.of(),user==null?null:1L,1L,null,null,"binding-a",EnterpriseConformanceTest.PROOF);}
    String place(RequestContext context){return store.transaction(EnterpriseConformanceTest.TENANT,false,tx->placement.place(tx,context,EnterpriseConformanceTest.NOW));}

    @Test void onlyEnrolledOnlineGroupMembersAreCandidates(){
        pool(Map.of("device","bob","late","bob","unenrolled","bob"));
        assertNull(place(context("none",null)));
        EnterpriseConformanceTest.endpoint(store,graph.codec,"late",EnterpriseConformanceTest.NOW-600000);
        assertNull(place(context("offline",null)),"a device past the offline threshold is never placed");
        EnterpriseConformanceTest.online(store,graph.codec,"device");
        for(int n=0;n<20;n++)assertEquals("device",place(context("only-"+n,null)));
    }
    @Test void aPoolSpreadsCallsAndAReplayKeepsItsDevice(){
        pool(Map.of("executor-1","bob","executor-2","bob","executor-3","bob"));
        for(var id:List.of("executor-1","executor-2","executor-3"))EnterpriseConformanceTest.online(store,graph.codec,id);
        var used=new TreeSet<String>();
        for(int n=0;n<60;n++){var chosen=place(context("call-"+n,null));assertEquals(chosen,place(context("call-"+n,null)));used.add(chosen);}
        assertEquals(Set.of("executor-1","executor-2","executor-3"),used);
    }
    @Test void theCallersOwnDeviceWinsOverThePool(){
        pool(Map.of("executor-1","bob","executor-2","bob","laptop","alice"));
        for(var id:List.of("executor-1","executor-2","laptop"))EnterpriseConformanceTest.online(store,graph.codec,id);
        for(int n=0;n<20;n++)assertEquals("laptop",place(context("own-"+n,"alice")));
        var service=new TreeSet<String>();for(int n=0;n<30;n++)service.add(place(context("service-"+n,null)));
        assertTrue(service.contains("executor-1")||service.contains("executor-2"),"a call with no user uses the whole group");
    }
    @Test void aDisabledBindingOrUnknownBindingPlacesNothing(){
        pool(Map.of("device","bob"));EnterpriseConformanceTest.online(store,graph.codec,"device");
        var c=context("x",null);
        assertNull(place(new RequestContext(c.requestId(),c.tenantId(),c.mode(),c.credentialKind(),null,null,"agent","workload",List.of(),null,1L,null,null,"missing",c.credentialSha256())));
        assertTrue(store.<Boolean>transaction(EnterpriseConformanceTest.TENANT,false,tx->placement.member(tx,"binding-a","device")));
        assertFalse(store.<Boolean>transaction(EnterpriseConformanceTest.TENANT,false,tx->placement.member(tx,"binding-a","elsewhere")));
    }
}
