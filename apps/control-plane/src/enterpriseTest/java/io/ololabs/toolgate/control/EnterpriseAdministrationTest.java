// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseAdministrationTest {
    @Test void completeImportPreservesVerifiedLoginObservationsSinceExport(){
        var intake=new IdentityIntake(store,graph.codec);long now=EnterpriseConformanceTest.NOW;
        var identity=intake.observe(administrator.tenant(),"https://idp.example","import-subject",null,null,now,now+900000,now);
        var snapshot=(com.fasterxml.jackson.databind.JsonNode)graph.codec.value(directory.export(administrator,false).body());long revision=snapshot.get("revision").asLong();
        intake.observe(administrator.tenant(),"https://idp.example","import-subject",null,null,now+61000,now+900000,now+61000);
        var body=graph.codec.json(Map.of("snapshot",snapshot,"mode","REPLACE","dryRun",false));
        assertEquals(200,directory.importConfig(administrator,body,false,revision,"roundtrip-observations","request").status());
        store.transaction(administrator.tenant(),false,tx->{var binding=tx.load().entries().values().stream().filter(e->e.id().kind()==Kind.IDENTITY_BINDING).map(e->graph.codec.model(e.document(),ControlIdentityBinding.class)).filter(b->b.userId().equals(identity.userId())).findFirst().orElseThrow();assertEquals(2L,binding.attemptCount());assertEquals(now+61000,binding.lastAttemptUnixMs());assertEquals(revision,tx.load().revision());return null;});
    }
    @Test void repeatedVerifiedLoginMetadataDoesNotRevokeUnrelatedApprovals(){
        var intake=new IdentityIntake(store,graph.codec);long now=EnterpriseConformanceTest.NOW;
        var first=intake.observe(administrator.tenant(),"https://idp.example","new-subject",null,null,now,now+900000,now);assertFalse(first.enabled());
        var before=store.transaction(administrator.tenant(),false,tx->List.of(tx.load().revision(),tx.enterprise().authorizationEpoch()));
        var repeated=intake.observe(administrator.tenant(),"https://idp.example","new-subject",null,null,now+61000,now+900000,now+61000);assertEquals(first,repeated);
        store.transaction(administrator.tenant(),false,tx->{assertEquals(before,List.of(tx.load().revision(),tx.enterprise().authorizationEpoch()));var identity=tx.load().entries().values().stream().filter(e->e.id().kind()==Kind.IDENTITY_BINDING).map(e->graph.codec.model(e.document(),ControlIdentityBinding.class)).filter(b->b.userId().equals(first.userId())).findFirst().orElseThrow();assertEquals(2L,identity.attemptCount());assertEquals(now+61000,identity.lastAttemptUnixMs());return null;});
    }
    @Test void individualProvenanceReportsCompleteTuplesWithoutCreatingDirectPermissions(){
        var value=graph.codec.model(new EffectiveAccess(store,graph.codec).get(administrator,Kind.USER,"alice").body(),EnterpriseEffectiveAccess.class);
        assertEquals(7L,value.directoryRevision());assertTrue(value.memberships().stream().anyMatch(m->m.groupId().equals("team-a")&&m.grants().stream().anyMatch(g->g.id().equals("human")&&g.scope().toolGroups().ids().equals(List.of("tools-a"))&&g.scope().deviceGroups().ids().equals(List.of("devices-a")))));
        assertFalse(graph.codec.json(value).contains("credentialSha256"));assertEquals(7L,store.<Long>transaction(administrator.tenant(),false,tx->tx.load().revision()));
    }
    @TempDir java.nio.file.Path temp;
    EnterpriseConformanceTest graph;PostgresStore store;DirectoryService directory;DirectoryService.Actor administrator;
    @BeforeEach void seedReviewedRecovery() {
        graph=new EnterpriseConformanceTest();graph.graph();var all=new GroupSelection(List.of(),true);
        var ceilings=new EnterpriseScope(all,all,List.of(),true,List.of(new EnterpriseResourceRule(ResourceKind.FILE,"",EnterpriseResourceMatch.ANY)),graph.conditions());
        var actions=List.of("read","create","update","delete","enable","disable","recover","manage-role","grant","import","export","audit","simulate","publish","policy","attest","rotate-credential");
        var rules=Arrays.stream(EnterpriseGroupType.values()).map(g->new EnterpriseManagementRule(actions,g,all,List.of(ceilings),graph.conditions())).toList();
        graph.add(Kind.ROLE,new ControlRole("recovery-role","Recovery role",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,rules));
        graph.add(Kind.TEAM,new ControlTeam("recovery-team","Reviewed administrators",true,1L,List.of("admin"),List.of("recovery-role")));
        var source=SqliteState.open(temp.resolve("state.sqlite"));store=new PostgresStore(source,graph.codec);store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),graph.directory());return null;});
        directory=new DirectoryService(store,graph.codec,512,1048576);administrator=directory.portalActor(EnterpriseConformanceTest.TENANT,"a".repeat(64),"admin");
    }
    @Test void allFourDefaultsAreProtectedFromDeletionAndDisablement() {
        for(var kind:GroupGraph.DEFAULTS.keySet()) {var id=GroupGraph.DEFAULTS.get(kind);assertThrows(Failure.class,()->directory.mutate(administrator,kind,id,"DELETE",null,1,"delete-"+id,"request"));var entry=store.transaction(administrator.tenant(),false,tx->tx.load().entries().get(kind.id(id)));var node=(com.fasterxml.jackson.databind.node.ObjectNode)graph.codec.value(entry.document());node.put("enabled",false);assertThrows(Failure.class,()->directory.mutate(administrator,kind,id,"UPDATE",graph.codec.json(node),1,"disable-"+id,"request"));}
    }
    @Test void newIndividualsJoinDefaultsAtomically() {
        var reply=directory.mutate(administrator,Kind.USER,"new-user","CREATE",graph.codec.json(new ControlUser("new-user","Pending review",false,1L)),0,"new-user-key","request");assertEquals(201,reply.status());
        store.transaction(administrator.tenant(),false,tx->{assertEquals(List.of("team-default"),GroupGraph.memberships(tx.load(),graph.codec,Kind.USER,"new-user",false));return null;});
    }
    @Test void everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership(){
        var tool=(com.fasterxml.jackson.databind.node.ObjectNode)graph.codec.value(graph.entries.get(Kind.TOOL.id("tool")).document());tool.put("id","new-tool");tool.put("enabled",false);((com.fasterxml.jackson.databind.node.ObjectNode)tool.get("definition")).put("id","new-tool");
        var records=Map.of(Kind.USER,graph.codec.json(new ControlUser("created-user","Created",false,1L)),Kind.AGENT,graph.codec.json(new ControlAgent("created-agent","Created",null,false,1L,"bob")),Kind.DEVICE,graph.codec.json(new ControlDevice("created-device","Created",false,1L,"bob")),Kind.TOOL,graph.codec.json(tool));
        var defaults=Map.of(Kind.USER,"team-default",Kind.AGENT,"default-agents",Kind.DEVICE,"default-devices",Kind.TOOL,"default-tools");
        for(var record:records.entrySet()){
            var id=((com.fasterxml.jackson.databind.JsonNode)graph.codec.value(record.getValue())).get("id").asText();
            assertEquals(201,directory.mutate(administrator,record.getKey(),id,"CREATE",record.getValue(),0,"create-"+id,"request").status());
            store.transaction(administrator.tenant(),false,tx->{assertEquals(List.of(defaults.get(record.getKey())),GroupGraph.memberships(tx.load(),graph.codec,record.getKey(),id,false));graph.codec.validatePolicies(tx.load());return null;});
        }
        assertThrows(Failure.class,()->directory.mutate(administrator,Kind.AGENT_GROUP,"group-a","DELETE",null,1,"delete-group-a","request"));
        directory.mutate(administrator,Kind.AGENT_GROUP,"group-b","DELETE",null,1,"delete-group-b","request");
        store.transaction(administrator.tenant(),false,tx->{assertEquals(List.of("group-a"),GroupGraph.memberships(tx.load(),graph.codec,Kind.AGENT,"agent",false));graph.codec.validatePolicies(tx.load());return null;});
    }
    @Test void lastMembershipCannotBeRemovedByDirectGroupEditing() {
        var group=graph.codec.model(directory.get(administrator,Kind.AGENT_GROUP,"group-a").body(),ControlAgentGroup.class);
        directory.mutate(administrator,Kind.AGENT_GROUP,group.id(),"UPDATE",graph.codec.json(new ControlAgentGroup(group.id(),group.name(),null,true,1L,List.of(),List.of())),1,"first","request");
        var failure=assertThrows(Failure.class,()->directory.mutate(administrator,Kind.AGENT_GROUP,"group-b","UPDATE",graph.codec.json(new ControlAgentGroup("group-b","Group B",null,true,1L,List.of(),List.of())),1,"second","request"));assertEquals(409,failure.status());
    }
    @Test void membershipReplacementRetainsExactlyOnePrimaryToolGroup() {
        var membership=new GroupMembership(EnterpriseMemberType.TOOL,"tool",List.of("tools-b"),7L);var reply=directory.setMemberships(administrator,Kind.TOOL,"tool",graph.codec.json(membership),7,"move","request");assertEquals(List.of("tools-b"),graph.codec.model(reply.body(),GroupMembership.class).groupIds());
        var invalid=new GroupMembership(EnterpriseMemberType.TOOL,"tool",List.of("tools-a","tools-b"),8L);assertThrows(Failure.class,()->directory.setMemberships(administrator,Kind.TOOL,"tool",graph.codec.json(invalid),8,"invalid-move","request"));
    }
    @Test void concurrentMembershipChangesHaveOneWinnerAndNoOrphan() throws Exception {
        var executor=Executors.newFixedThreadPool(2);try {var membership=new GroupMembership(EnterpriseMemberType.AGENT,"agent",List.of("group-a"),7L);var body=graph.codec.json(membership);var first=executor.submit(()->{try{directory.setMemberships(administrator,Kind.AGENT,"agent",body,7,"concurrent-a","request");return true;}catch(Failure conflict){assertEquals(409,conflict.status());return false;}});var second=executor.submit(()->{try{directory.setMemberships(administrator,Kind.AGENT,"agent",body,7,"concurrent-b","request");return true;}catch(Failure conflict){assertEquals(409,conflict.status());return false;}});assertNotEquals(first.get(),second.get());}finally{executor.shutdownNow();}
        store.transaction(administrator.tenant(),false,tx->{graph.codec.validatePolicies(tx.load());return null;});
    }
    @Test void recoveryAdministratorCannotBeDisabledOrLoseRecoveryMembership() {
        assertThrows(Failure.class,()->directory.mutate(administrator,Kind.USER,"admin","UPDATE",graph.codec.json(new ControlUser("admin","Administrator",false,1L)),1,"disable-last","request"));
        var membership=new GroupMembership(EnterpriseMemberType.USER,"admin",List.of("team-default"),7L);assertThrows(Failure.class,()->directory.setMemberships(administrator,Kind.USER,"admin",graph.codec.json(membership),7,"remove-recovery","request"));
    }
    @Test void externalRoleClaimWithoutBoundManagementMembershipDoesNotAuthorize() {var claim=new DirectoryService.Actor(administrator.tenant(),"b".repeat(64),true,true);assertThrows(Failure.class,()->directory.requirePortal(claim,null));assertThrows(Failure.class,()->directory.mutate(claim,Kind.USER,"new","CREATE",graph.codec.json(new ControlUser("new","New",false,1L)),0,"key","request"));}
    @Test void managementCeilingRejectsAccessExpansionAndDirectRuntimeRoles() {
        var narrow=graph.scope("tools-a","devices-a");var wide=graph.scope("tools-a","devices-b");assertFalse(ManagementAccess.contains(narrow,wide));assertTrue(ManagementAccess.contains(narrow,narrow));
        assertThrows(Failure.class,()->directory.requirePortal(administrator,"alice"));
    }
    @Test void mutationReplayStillRequiresCurrentAuthority() {
        var body=graph.codec.json(new ControlUser("new-user","Pending",false,1L));var original=directory.mutate(administrator,Kind.USER,"new-user","CREATE",body,0,"stable-retry","request");assertEquals(original,directory.mutate(administrator,Kind.USER,"new-user","CREATE",body,0,"stable-retry","request"));
        store.transaction(administrator.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var role=graph.codec.model(entries.get(Kind.ROLE.id("recovery-role")).document(),ControlRole.class);entries.put(Kind.ROLE.id(role.id()),graph.codec.entry(Kind.ROLE,graph.codec.json(new ControlRole(role.id(),role.name(),false,2L,role.portalRole(),role.roleType(),role.managementRules()))));tx.save(before,new Directory(before.revision()+1,entries));return null;});
        assertThrows(Failure.class,()->directory.mutate(administrator,Kind.USER,"new-user","CREATE",body,0,"stable-retry","request"));
    }
    @Test void identifiersRemainRetiredAfterDeletion() {
        var group=new ControlToolGroup("empty-group","Empty group",true,1L,List.of());directory.mutate(administrator,Kind.TOOL_GROUP,group.id(),"CREATE",graph.codec.json(group),0,"create-empty","request");directory.mutate(administrator,Kind.TOOL_GROUP,group.id(),"DELETE",null,1,"delete-empty","request");assertThrows(Failure.class,()->directory.mutate(administrator,Kind.TOOL_GROUP,group.id(),"CREATE",graph.codec.json(group),0,"recreate-empty","request"));
    }
    @Test void superAdministratorWithoutScopeRulesRetainsManagementAuthority(){
        store.transaction(administrator.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());entries.put(Kind.ROLE.id("recovery-role"),graph.codec.entry(Kind.ROLE,graph.codec.json(new ControlRole("recovery-role","Super Admin",true,2L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of()))));tx.save(before,new Directory(before.revision()+1,entries));return null;});
        directory.requirePortal(administrator,"admin");assertEquals(201,directory.mutate(administrator,Kind.USER,"exceptional-user","CREATE",graph.codec.json(new ControlUser("exceptional-user","Pending review",false,1L)),0,"exceptional-user-key","request").status());assertThrows(Failure.class,()->directory.mutate(administrator,Kind.USER,"admin","UPDATE",graph.codec.json(new ControlUser("admin","Admin",false,1L)),1,"disable-super","request"));
    }
    @Test void shadowComparesCapturedLegacyDecisionWithoutApplyingGrantsOrConsumingAuthority(){
        var proposed=graph.codec.model(graph.codec.snapshot(administrator.tenant(),graph.directory(),false),ControlSnapshot.class);var evaluation=graph.input(graph.context(EnterpriseRequestMode.HUMAN,"binding-a"),List.of(new ResourceDescriptor(ResourceKind.FILE,"data/report.txt")));
        var before=store.transaction(administrator.tenant(),false,tx->List.of(tx.load().revision(),tx.enterprise().authorizationEpoch(),tx.enterprise().invocationsSince(0)));
        var result=graph.codec.model(directory.shadow(administrator,graph.codec.json(new EnterpriseShadowRequest(proposed,evaluation,Decision.BLOCK,EnterpriseConformanceTest.DIGEST))).body(),EnterpriseShadowResult.class);assertEquals(Decision.ALLOW,result.decision().decision());assertTrue(result.accessExpansion());assertEquals(before,store.transaction(administrator.tenant(),false,tx->List.of(tx.load().revision(),tx.enterprise().authorizationEpoch(),tx.enterprise().invocationsSince(0))));
    }

}
