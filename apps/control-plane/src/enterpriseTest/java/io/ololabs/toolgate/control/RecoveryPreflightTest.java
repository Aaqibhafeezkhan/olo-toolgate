// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryPreflightTest {
    @TempDir Path temp;
    TargetLookupLifecycleTest fixture;EnterpriseOperationTest rig;
    @BeforeAll static void key()throws Exception{TargetLookupLifecycleTest.key();}
    @BeforeEach void setup()throws Exception{
        fixture=new TargetLookupLifecycleTest();fixture.temp=temp;fixture.setup();rig=fixture.rig;
    }
    RecoveryPreflight preflight(){
        var tenant=rig.gateway.tenant();
        return new RecoveryPreflight(rig.store,rig.graph.codec,new ServerPackages(rig.store,rig.graph.codec,rig.operations,rig.clock),
            (after,limit)->tenant.value().compareTo(after)>0?List.of(tenant):List.of());
    }
    @Test void reviewedLookupRemainsAvailableButAnUnqualifiedExecutorNeverBecomesRetryReady(){
        var page=preflight().operatorPage(null,null,50,rig.clock.millis());
        assertEquals(1,page.items().size());assertEquals("CURRENT_ONLY",page.buildMode());assertNull(page.nextCursor());
        var tool=page.items().getFirst();assertFalse(tool.automaticResendEligible());
        assertEquals(RecoveryReadinessReason.UNQUALIFIED_EXECUTOR,tool.reason());
        assertEquals(ReconciliationMethod.STATUS_LOOKUP,tool.effectiveMethod());
        assertEquals(RecoveryReadinessReason.ELIGIBLE,tool.routes().getFirst().reason());
        assertTrue(tool.routes().getFirst().lookupAvailable());
        var encoded=rig.graph.codec.json(page);rig.graph.codec.model(encoded,RecoveryPreflightPage.class);
        assertFalse(encoded.contains("tg-"));assertFalse(encoded.contains("provider-receipt"));assertFalse(encoded.contains("cipher"));
    }
    @Test void reviewerRevocationAndExpiryAreReportedWithoutAssumingATargetWindow(){
        fixture.fixture.revokeReviewer();
        var tool=preflight().operatorPage(null,null,50,rig.clock.millis()).items().getFirst();
        assertEquals(RecoveryReadinessReason.INDEPENDENT_REVIEW_REQUIRED,tool.reason());
        assertEquals(ReconciliationMethod.MANUAL,tool.effectiveMethod());assertFalse(tool.automaticResendEligible());
        var expired=preflight().operatorPage(null,null,50,rig.clock.millis()+86_400_001).items().getFirst();
        assertEquals(RecoveryReadinessReason.REVIEW_EXPIRED,expired.reason());
    }
    void copyTool(String id){
        var original=rig.graph.entries.get(Ids.Kind.TOOL.id("acme.test/tool"));
        var doc=(ObjectNode)rig.graph.codec.value(original.document());doc.put("id",id).put("revision",1);
        ((ObjectNode)doc.path("definition")).put("id",id);
        rig.graph.entries.put(Ids.Kind.TOOL.id(id),rig.graph.codec.entry(Ids.Kind.TOOL,doc.toString()));rig.saveGraph();
    }
    @Test void boundedCursorsResumeExactlyAndFenceChangedDirectoryState(){
        copyTool("acme.test/z-second");var inventory=preflight();
        var first=inventory.operatorPage(null,null,1,rig.clock.millis());assertEquals(1,first.items().size());assertNotNull(first.nextCursor());
        var next=inventory.operatorPage(null,first.nextCursor(),1,rig.clock.millis());
        assertEquals("acme.test/z-second",next.items().getFirst().toolId());assertNull(next.nextCursor());
        copyTool("acme.test/third");
        assertEquals(409,assertThrows(Failure.class,()->inventory.operatorPage(null,first.nextCursor(),1,rig.clock.millis())).status());
        assertThrows(Failure.class,()->inventory.operatorPage("not-a-uuid",null,50,rig.clock.millis()));
        assertThrows(Failure.class,()->inventory.operatorPage(null,"old-cursor-shape",50,rig.clock.millis()));
        assertThrows(Failure.class,()->inventory.operatorPage(null,null,101,rig.clock.millis()));
    }
    @Test void tenantPortalInventoryUsesCurrentToolReadScope(){
        var caller=new DirectoryService.Actor(rig.gateway.tenant(),DirectoryService.digest("bob"),false,false,"bob");
        assertTrue(preflight().page(caller,null,50,rig.clock.millis()).items().isEmpty());
        assertEquals(1,preflight().page(fixture.operator(),null,50,rig.clock.millis()).items().size());
    }
}
