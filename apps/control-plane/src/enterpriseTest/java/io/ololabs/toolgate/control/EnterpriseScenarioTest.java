// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.ololabs.toolgate.control.EnterpriseConformanceTest.*;

/** Remaining acceptance boundaries: independent dimensions, scoped roles and bounded chains. */
class EnterpriseScenarioTest {
    EnterpriseConformanceTest f;
    @BeforeEach void setup(){f=new EnterpriseConformanceTest();f.graph();}
    void enabled(Kind kind,String id,boolean value){
        var document=(ObjectNode)f.codec.value(f.entries.get(kind.id(id)).document());
        document.put("enabled",value);f.entries.put(kind.id(id),f.codec.entry(kind,f.codec.json(document)));
    }
    EnterpriseDecision human(){return f.decide(f.context(EnterpriseRequestMode.HUMAN,"binding-a"));}
    EnterpriseDecision delegated(){return f.decide(f.context(EnterpriseRequestMode.DELEGATED,"binding-a"));}
    EnterpriseScope scope(EnterpriseConditions conditions){var s=f.scope("tools-a","devices-a");return new EnterpriseScope(s.toolGroups(),s.deviceGroups(),s.actions(),s.allActions(),s.resources(),conditions);}
    void condition(EnterpriseConditions c){f.grant("human","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,scope(c));}
    EnterpriseDecision input(String action,boolean online,Long amount,Long count){
        var r=f.input(f.context(EnterpriseRequestMode.HUMAN,"binding-a"),List.of(new ResourceDescriptor(ResourceKind.FILE,"data/report.txt")));
        return new EnterpriseEvaluator(f.codec).evaluate(TENANT,f.directory(),new EnterpriseEvaluation(r.context(),r.toolId(),action,r.argumentsDigest(),r.resources(),r.toolDigest(),r.packageDigest(),r.nowUnixMs(),r.authorityRevision(),online,amount,null),count);
    }
    @Test void eachRequiredAuthorityDimensionIndependentlyDenies(){
        var dimensions=Map.ofEntries(Map.entry(Kind.USER,"alice"),Map.entry(Kind.TEAM,"team-a"),Map.entry(Kind.AGENT,"agent"),Map.entry(Kind.AGENT_GROUP,"group-a"),Map.entry(Kind.DELEGATION,"delegation"),Map.entry(Kind.WORKLOAD_BINDING,"workload"),Map.entry(Kind.TOOL,"tool"),Map.entry(Kind.TOOL_GROUP,"tools-a"),Map.entry(Kind.EXTRACTOR,"extractor"),Map.entry(Kind.DEVICE,"device"),Map.entry(Kind.DEVICE_GROUP,"devices-a"),Map.entry(Kind.BINDING,"binding-a"),Map.entry(Kind.IDENTITY_BINDING,"identity-alice"));
        for(var dimension:dimensions.entrySet()){f.graph();assertEquals(Decision.ALLOW,delegated().decision());enabled(dimension.getKey(),dimension.getValue(),false);assertEquals(Decision.BLOCK,delegated().decision(),dimension.toString());}
        for(var id:List.of("human","capability")){f.graph();enabled(Kind.GRANT,id,false);assertEquals(Decision.BLOCK,delegated().decision(),id);}
    }
    @Test void roleInheritanceRetainsIndependentTeamWitnessesAndRevokesLive(){
        f.remove(Kind.GRANT,"human");f.add(Kind.ROLE,new ControlRole("reporting","Reporting",true,1L,UserRole.BASIC,EnterpriseRoleType.HUMAN,List.of()));
        for(var team:List.of("team-a","team-b"))f.add(Kind.TEAM,new ControlTeam(team,team,true,1L,List.of("alice"),List.of("reporting")));
        f.grant("reporting-grant","reporting",EnterpriseSourceType.ROLE,EnterpriseGrantPurpose.HUMAN,f.scope("tools-a","devices-a"));
        assertEquals(Set.of("team-a","team-b"),new HashSet<>(human().witnesses().stream().map(EnterpriseWitness::teamId).toList()));
        f.add(Kind.TEAM,new ControlTeam("team-a","Team A",true,2L,List.of(),List.of("reporting")));
        assertEquals("team-b",human().witnesses().getFirst().teamId());
        enabled(Kind.ROLE,"reporting",false);assertEquals(Decision.BLOCK,human().decision());
    }
    @Test void actionRulesDoNotPromoteReadToWrite(){
        var s=f.scope("tools-a","devices-a");f.grant("human","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,new EnterpriseScope(s.toolGroups(),s.deviceGroups(),List.of("read"),false,s.resources(),s.conditions()));
        assertEquals(Decision.ALLOW,input("read",true,null,null).decision());assertEquals(Decision.BLOCK,input("write",true,null,null).decision());
        f.add(Kind.ROLE,new ControlRole("writer","Writer",true,1L,UserRole.BASIC,EnterpriseRoleType.HUMAN,List.of()));f.add(Kind.TEAM,new ControlTeam("team-a","Team A",true,1L,List.of("alice"),List.of("writer")));
        f.grant("write","writer",EnterpriseSourceType.ROLE,EnterpriseGrantPurpose.HUMAN,new EnterpriseScope(s.toolGroups(),s.deviceGroups(),List.of("write"),false,s.resources(),s.conditions()));
        assertEquals(Decision.ALLOW,input("write",true,null,null).decision());f.policy("block",Decision.BLOCK);assertEquals(Decision.BLOCK,input("write",true,null,null).decision());
    }
    @Test void jitGrantValidityHasExactExpiryAndNoImplicitExtension(){
        condition(new EnterpriseConditions(NOW-1,NOW+1,List.of(),List.of(),List.of(),List.of(),true,false,null,null));assertEquals(Decision.ALLOW,human().decision());assertEquals(NOW+1,human().validUntilUnixMs());
        condition(new EnterpriseConditions(0L,NOW,List.of(),List.of(),List.of(),List.of(),true,false,null,null));assertEquals(Decision.BLOCK,human().decision());
        condition(new EnterpriseConditions(NOW+1,NOW+1000,List.of(),List.of(),List.of(),List.of(),true,false,null,null));assertEquals(Decision.BLOCK,human().decision());
    }
    @Test void trustedNetworkPostureRegionAndHoursAreConjunctive(){
        var now=Instant.ofEpochMilli(NOW).atZone(ZoneOffset.UTC);long day=now.getDayOfWeek().getValue()%7;long minute=now.getHour()*60+now.getMinute();
        var validHours=List.of(new EnterpriseHours(day,minute,minute+1));
        condition(new EnterpriseConditions(0L,0L,List.of("10.0.0.0/8"),List.of("managed"),List.of("in"),validHours,true,false,null,null));assertEquals(Decision.ALLOW,human().decision());
        var invalid=List.of(new EnterpriseConditions(0L,0L,List.of("192.0.2.0/24"),List.of("managed"),List.of("in"),validHours,true,false,null,null),new EnterpriseConditions(0L,0L,List.of("10.0.0.0/8"),List.of("secure"),List.of("in"),validHours,true,false,null,null),new EnterpriseConditions(0L,0L,List.of("10.0.0.0/8"),List.of("managed"),List.of("us"),validHours,true,false,null,null),new EnterpriseConditions(0L,0L,List.of("10.0.0.0/8"),List.of("managed"),List.of("in"),List.of(new EnterpriseHours((day+1)%7,0L,1440L)),true,false,null,null));
        for(var c:invalid){condition(c);assertEquals(Decision.BLOCK,human().decision());}
        f.remove(Kind.DEVICE_EVIDENCE,"evidence");assertEquals(Decision.BLOCK,human().decision());
    }
    @Test void highRiskRequiresOnlineAndAuthoritativeAmountAndQuota(){
        condition(new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),true,true,100L,2L));
        assertEquals(Decision.ALLOW,input("read",true,100L,1L).decision());
        assertEquals(Decision.BLOCK,input("read",false,100L,1L).decision());
        assertEquals(Decision.BLOCK,input("read",true,null,1L).decision());
        assertEquals(Decision.BLOCK,input("read",true,101L,1L).decision());
        assertEquals(Decision.BLOCK,input("read",true,100L,null).decision());
        assertEquals(Decision.BLOCK,input("read",true,100L,2L).decision());
    }
    @Test void toolMoveAndDeviceRemovalCannotRetainOriginalBinding(){
        f.add(Kind.TOOL_GROUP,new ControlToolGroup("tools-a","Tools A",true,2L,List.of()));f.add(Kind.TOOL_GROUP,new ControlToolGroup("tools-b","Tools B",true,2L,List.of("tool")));
        assertEquals(Decision.BLOCK,human().decision());f.graph();
        f.add(Kind.DEVICE_GROUP,new ControlDeviceGroup("devices-a","Devices A",true,2L,List.of()));
        assertEquals(Decision.BLOCK,human().decision());assertEquals(Decision.BLOCK,f.decide(f.context(EnterpriseRequestMode.HUMAN,"binding-b")).decision());
    }
    @Test void administrativeOwnerSuppliesNoRightsAndDependencyIsExplicit(){
        enabled(Kind.USER,"bob",false);assertEquals(Decision.ALLOW,delegated().decision());
        f.add(Kind.BINDING,new ControlExecutionBinding("binding-a","Binding A",true,1L,"tools-a","devices-a",List.of("read","write"),List.of(DIGEST),true,true));
        assertEquals(Decision.BLOCK,delegated().decision());
    }
    @Test void downstreamAgentCannotExceedAnyUpstreamCeilingOrReuseIdentity(){
        f.add(Kind.AGENT,new ControlAgent("downstream","Downstream",null,true,1L,"bob"));f.add(Kind.AGENT_GROUP,new ControlAgentGroup("downstream-group","Downstream",null,true,1L,List.of("downstream"),List.of()));
        f.workload("child-workload","downstream",EnterpriseRequestMode.DELEGATED,"alice","workload");
        f.grant("child-capability","downstream-group",EnterpriseSourceType.AGENT_GROUP,EnterpriseGrantPurpose.CAPABILITY,f.scope("tools-a","devices-a"));
        f.add(Kind.AGENT_DELEGATION,new ControlAgentDelegation("edge","Edge",true,1L,"group-a","downstream-group",f.scope("tools-a","devices-a"),1L));
        var chain=new EnterpriseContext("request",TENANT.value(),EnterpriseRequestMode.DELEGATED,"alice","downstream","child-workload",List.of(new EnterpriseActorHop("agent","workload")),1L,1L,"binding-a","device");
        assertEquals(Decision.ALLOW,f.decide(chain).decision());
        enabled(Kind.GRANT,"capability",false);assertEquals(Decision.BLOCK,f.decide(chain).decision());enabled(Kind.GRANT,"capability",true);
        var s=f.scope("tools-a","devices-b");f.add(Kind.AGENT_DELEGATION,new ControlAgentDelegation("edge","Edge",true,1L,"group-a","downstream-group",s,1L));assertEquals(Decision.BLOCK,f.decide(chain).decision());
        var cycle=new EnterpriseContext("request",TENANT.value(),EnterpriseRequestMode.DELEGATED,"alice","agent","workload",List.of(new EnterpriseActorHop("agent","workload")),1L,1L,"binding-a","device");assertEquals(Decision.BLOCK,f.decide(cycle).decision());
    }
    @Test void disabledAgentGroupRemovesItsPathWhileIndependentPathsSurvive(){
        f.grant("other-capability","group-b",EnterpriseSourceType.AGENT_GROUP,EnterpriseGrantPurpose.CAPABILITY,f.scope("tools-a","devices-a"));f.add(Kind.DELEGATION,new ControlDelegation("other-delegation","Other",true,1L,"team-a","group-b",f.scope("tools-a","devices-a")));
        enabled(Kind.AGENT_GROUP,"group-a",false);assertEquals(Decision.ALLOW,delegated().decision());assertEquals("group-b",delegated().witnesses().getFirst().agentGroupId());
        enabled(Kind.AGENT_GROUP,"group-b",false);assertEquals(Decision.BLOCK,delegated().decision());
    }
}
