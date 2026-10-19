// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ololabs.toolgate.control.adapter.PostgresStore;
import io.ololabs.toolgate.control.adapter.SqliteState;
import static org.junit.jupiter.api.Assertions.*;

class StandardAccessPresetsTest {
    @TempDir java.nio.file.Path temp;
    EnterpriseConformanceTest setup(String actor,String tool,String device) {
        var f=new EnterpriseConformanceTest();f.graph();f.remove(Kind.GRANT,"human");f.remove(Kind.GRANT,"capability");
        StandardAccessPresets.install(f.entries,f.codec);
        GroupGraph.setMemberships(f.entries,f.codec,Kind.USER,"alice",List.of(actor+"Team"));
        GroupGraph.setMemberships(f.entries,f.codec,Kind.AGENT,"agent",List.of(actor+"AgentGroup"));
        GroupGraph.setMemberships(f.entries,f.codec,Kind.TOOL,"tool",List.of(tool+"ToolGroup"));
        GroupGraph.setMemberships(f.entries,f.codec,Kind.DEVICE,"device",List.of(device+"DeviceGroup"));
        var id="standard-"+tool+"-"+device;
        var binding=f.codec.model(f.entries.get(Kind.BINDING.id(id)).document(),ControlExecutionBinding.class);
        f.add(Kind.BINDING,new ControlExecutionBinding(id,id,true,2L,binding.toolGroupId(),binding.deviceGroupId(),binding.actions(),List.of(EnterpriseConformanceTest.DIGEST),true,false));
        f.workload("workload","agent",RequestMode.SERVICE,null,null);f.codec.validatePolicies(f.directory());return f;
    }
    EnterpriseDecision decide(EnterpriseConformanceTest f,String action,RequestMode mode,String tool,String device,boolean online) {
        var r=f.input(f.context(mode,"standard-"+tool+"-"+device),List.of(new ResourceDescriptor(ResourceKind.FILE,"data/report.txt")));
        return new EnterpriseEvaluator(f.codec).evaluate(EnterpriseConformanceTest.TENANT,f.directory(),new EnterpriseEvaluation(r.context(),r.toolId(),action,r.argumentsDigest(),r.resources(),r.toolDigest(),r.packageDigest(),r.nowUnixMs(),r.authorityRevision(),online,null,null));
    }
    @Test void allActorToolDeviceCombinationsEnforceTheLowestLevelForHumansAndServices() {
        var tiers=List.of("ReadOnly","ReadAndWrite","Admin");
        for(var actor:tiers)for(var tool:tiers)for(var device:tiers)for(var mode:List.of(RequestMode.HUMAN,RequestMode.SERVICE)) {
            var f=setup(actor,tool,device);assertEquals(Decision.ALLOW,decide(f,"read",mode,tool,device,true).decision());
            var expected=actor.equals("ReadOnly")||tool.equals("ReadOnly")||device.equals("ReadOnly")?Decision.BLOCK:Decision.ALLOW;
            assertEquals(expected,decide(f,"write",mode,tool,device,true).decision(),actor+"/"+tool+"/"+device+"/"+mode);
            assertEquals(Decision.BLOCK,decide(f,"read",mode,tool,device,false).decision());
        }
    }
    @Test void adminCannotBypassReadonlyResourceGroupsWithAnOverbroadBinding() {
        for(var pair:List.of(List.of("ReadOnlyToolGroup","AdminDeviceGroup"),List.of("AdminToolGroup","ReadOnlyDeviceGroup")))
            assertThrows(IllegalArgumentException.class,()->StandardAccessPresets.validateBinding(new ControlExecutionBinding("bad","Bad",true,1L,pair.get(0),pair.get(1),List.of("write"),List.of(EnterpriseConformanceTest.DIGEST),true,false)));
    }
    @Test void defaultsAndUnpinnedPackagesStillDenyAfterInstallingPresets() {
        var f=setup("Admin","Admin","Admin");
        GroupGraph.setMemberships(f.entries,f.codec,Kind.AGENT,"agent",List.of("default-agents"));
        assertEquals(Decision.BLOCK,decide(f,"read",RequestMode.SERVICE,"Admin","Admin",true).decision());
        f=setup("Admin","Admin","Admin");
        f.add(Kind.BINDING,new ControlExecutionBinding("standard-Admin-Admin","No approved package",true,2L,"AdminToolGroup","AdminDeviceGroup",List.of("read"),List.of(),true,false));
        assertEquals(EnterpriseDecisionReason.NO_BINDING,decide(f,"read",RequestMode.SERVICE,"Admin","Admin",true).reason());
    }
    @Test void sensitiveAdminExecutionStillRequiresAnIndependentRuntimeReview() {
        var f=setup("Admin","Admin","Admin");
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)f.codec.value(f.entries.get(Kind.TOOL.id("tool")).document());
        var action=((com.fasterxml.jackson.databind.node.ArrayNode)node.path("definition").path("actions")).addObject();
        action.put("name","execute");action.putArray("resourceKinds").add("FILE");
        f.entries.put(Kind.TOOL.id("tool"),f.codec.entry(Kind.TOOL,f.codec.json(node)));
        for(var mode:List.of(RequestMode.HUMAN,RequestMode.SERVICE)){
            var result=decide(f,"execute",mode,"Admin","Admin",true);
            assertEquals(Decision.ASK,result.decision());assertTrue(result.obligations().contains("standard-admin-sensitive-review"));
        }
    }
    @Test void adminCannotElevateThroughMembershipOrDirectTeamEditing() {
        var f=setup("Admin","Admin","Admin");
        var role=f.codec.model(f.entries.get(Kind.ROLE.id("standard-Admin-management")).document(),ControlRole.class);
        f.add(Kind.ROLE,new ControlRole("root-role","Super Admin",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,role.managementRules()));
        f.add(Kind.TEAM,new ControlTeam("root-team","Root administrators",true,1L,List.of("bob"),List.of("root-role")));
        var store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("ceilings.sqlite"))),f.codec);
        store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),f.directory());return null;});
        var directory=new DirectoryService(store,f.codec,512,1048576);
        var actor=directory.portalActor(EnterpriseConformanceTest.TENANT,"a".repeat(64),"alice");
        var body=f.codec.json(new GroupMembership(EnterpriseMemberType.USER,"alice",List.of("root-team"),7L));
        assertEquals(403,assertThrows(Failure.class,()->directory.setMemberships(actor,Kind.USER,"alice",body,7,"allocate","request")).status());
        var group=new ControlTeam("root-team","Root administrators",true,1L,List.of("bob","alice"),List.of("root-role"));
        assertEquals(403,assertThrows(Failure.class,()->directory.mutate(actor,Kind.TEAM,"root-team","UPDATE",f.codec.json(group),1,"edit-team","request")).status());
    }
}
