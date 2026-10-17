// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Backup without users, restore onto another instance with resolved identities, Replace preservation and credential rotation. */
class ConfigurationTransferTest {
    @TempDir java.nio.file.Path temp;EnterpriseAdministrationTest source;ContractCodec codec;PostgresStore target;DirectoryService restore;DirectoryService.Actor owner;
    @BeforeEach void setup(){
        source=new EnterpriseAdministrationTest();source.temp=temp;source.seedReviewedRecovery();codec=source.graph.codec;
        target=new PostgresStore(SqliteState.open(temp.resolve("target.sqlite")),codec);
        target.transaction(EnterpriseConformanceTest.TENANT,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());GroupGraph.defaults(entries,codec);
            for(var user:List.of("owner","carol")){var e=codec.entry(Kind.USER,codec.json(new ControlUser(user,user,true,1L)));entries.put(e.id(),e);}GroupGraph.addDefault(entries,codec,Kind.USER,"owner");
            var role=codec.entry(Kind.ROLE,codec.json(new ControlRole("owner-role","Super Admin",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of())));entries.put(role.id(),role);
            for(var team:List.of(new ControlTeam("owners","Owners",true,1L,List.of("owner"),List.of("owner-role")),new ControlTeam("ops","Operations",true,1L,List.of("carol"),List.of()))){var e=codec.entry(Kind.TEAM,codec.json(team));entries.put(e.id(),e);}
            tx.save(before,new Directory(before.revision()+1,entries));return null;});
        restore=new DirectoryService(target,codec,512,1048576);owner=restore.portalActor(EnterpriseConformanceTest.TENANT,"e".repeat(64),"owner");
    }
    ObjectNode exported(){return (ObjectNode)codec.value(source.directory.export(source.administrator,false).body());}
    String request(ObjectNode snapshot,String mode,boolean dryRun,Map<String,Object> resolution,boolean remove){var body=new HashMap<String,Object>(Map.of("snapshot",snapshot,"mode",mode,"dryRun",dryRun));if(!resolution.isEmpty())body.put("identityResolution",resolution);if(remove)body.put("removeUnlistedMemberships",true);return codec.json(body);}
    long revision(PostgresStore store){return store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.load().revision());}
    <T> T record(PostgresStore store,Kind kind,String id,Class<T> type){return store.transaction(EnterpriseConformanceTest.TENANT,false,tx->{var e=tx.load().entries().get(kind.id(id));return e==null?null:codec.model(e.document(),type);});}
    List<String> teams(PostgresStore store,String user){return store.transaction(EnterpriseConformanceTest.TENANT,false,tx->GroupGraph.memberships(tx.load(),codec,Kind.USER,user,false));}

    @Test void exportLeavesUsersOutAndListsRequiredIdentitiesWithTheirTeams(){
        var snapshot=exported();assertTrue(snapshot.get("users").isEmpty());assertTrue(snapshot.get("identityBindings").isEmpty());assertTrue(snapshot.get("deviceEvidence").isEmpty());
        for(var team:snapshot.get("teams"))assertTrue(team.get("userIds").isEmpty());
        assertEquals(1,snapshot.get("delegations").size());assertEquals(1,snapshot.get("extractors").size());assertEquals(1,snapshot.get("workloadBindings").size());
        var required=new HashMap<String,List<String>>();for(var r:snapshot.get("requiredIdentities")){var t=new ArrayList<String>();r.get("teamIds").forEach(x->t.add(x.asText()));required.put(r.get("id").asText(),t);}
        assertEquals(List.of("team-a","team-b"),required.get("alice"));assertEquals(List.of("team-default"),required.get("bob"));assertEquals(List.of("recovery-team","team-default"),required.get("admin"));
        assertEquals(2,snapshot.get("settings").get("configuration").get("requiredReviews").asInt());
    }
    @Test void restoreRefusesUnresolvedIdentitiesThenResolvesExistingMapAndPlaceholder(){
        var snapshot=exported();snapshot.put("revision",revision(target));snapshot.remove("settings");
        var unresolved=assertThrows(Failure.class,()->restore.importConfig(owner,request(snapshot,"MERGE",true,Map.of(),false),false,revision(target),"dry","request"));
        assertEquals(409,unresolved.status());assertEquals(ControlErrorDetailCode.UNRESOLVED_IDENTITIES,unresolved.detail());assertEquals(List.of("admin","alice","bob"),unresolved.ids());
        var wrong=Map.<String,Object>of("alice",Map.of("action","EXISTING"),"bob",Map.of("action","PLACEHOLDER"),"admin",Map.of("action","MAP","targetUserId","owner"));
        assertEquals(List.of("alice"),assertThrows(Failure.class,()->restore.importConfig(owner,request(snapshot,"MERGE",false,wrong,false),false,revision(target),"wrong","request")).ids());
        var resolution=Map.<String,Object>of("alice",Map.of("action","MAP","targetUserId","carol","teamIds",List.of("team-a")),"bob",Map.of("action","PLACEHOLDER"),"admin",Map.of("action","MAP","targetUserId","owner"));
        assertEquals(200,restore.importConfig(owner,request(snapshot,"MERGE",false,resolution,false),false,revision(target),"restore","request").status());
        // Carol keeps Operations and gains the ticked team; bob is a disabled placeholder owning the restored agent and device.
        assertEquals(List.of("ops","team-a"),teams(target,"carol"));assertFalse(record(target,Kind.USER,"bob",ControlUser.class).enabled());
        assertEquals("bob",record(target,Kind.AGENT,"agent",ControlAgent.class).ownerUserId());assertNotNull(record(target,Kind.DELEGATION,"delegation",ControlDelegation.class));assertNotNull(record(target,Kind.EXTRACTOR,"extractor",ControlResourceExtractor.class));
        var workload=record(target,Kind.WORKLOAD_BINDING,"workload",ControlWorkloadBinding.class);assertEquals("carol",workload.delegatedUserId());assertFalse(workload.enabled());assertTrue(workload.needsReauthorization());
        // Removal from teams outside the backup is a separate, explicit choice.
        var again=exported();again.put("revision",revision(target));again.remove("settings");
        assertEquals(200,restore.importConfig(owner,request(again,"MERGE",false,resolution,true),false,revision(target),"remove","request").status());assertEquals(List.of("team-a"),teams(target,"carol"));
    }
    @Test void replacePreservesExcludedKindsAndRefusesDanglingPreservedRecords(){
        var snapshot=exported();((ArrayNode)snapshot.get("delegations")).removeAll();
        var body=request(snapshot,"REPLACE",false,Map.of(),false);assertEquals(200,source.directory.importConfig(source.administrator,body,false,snapshot.get("revision").asLong(),"replace","request").status());
        assertNull(record(source.store,Kind.DELEGATION,"delegation",ControlDelegation.class));assertNotNull(record(source.store,Kind.USER,"alice",ControlUser.class));
        assertEquals(List.of("team-a","team-b"),teams(source.store,"alice"));assertNotNull(record(source.store,Kind.IDENTITY_BINDING,"identity-alice",ControlIdentityBinding.class));assertNotNull(record(source.store,Kind.DEVICE_EVIDENCE,"evidence",ControlDeviceEvidence.class));
        var without=exported();((ArrayNode)without.get("devices")).removeAll();for(var group:without.get("deviceGroups"))((ArrayNode)group.get("deviceIds")).removeAll();
        var conflict=assertThrows(Failure.class,()->source.directory.importConfig(source.administrator,request(without,"REPLACE",true,Map.of(),false),false,without.get("revision").asLong(),"dangling","request"));
        assertEquals(409,conflict.status());assertEquals(ControlErrorDetailCode.REPLACE_CONFLICTS,conflict.detail());assertEquals(List.of("DEVICE_EVIDENCE:evidence"),conflict.ids());
    }
    @Test void settingsImportsNeedFeaturePermissionsAndCannotMoveTheReviewThreshold(){
        var snapshot=exported();((ObjectNode)snapshot.get("settings").get("gateway")).put("gatewayName","Restored Gateway");
        assertEquals(200,source.directory.importConfig(source.administrator,request(snapshot,"MERGE",false,Map.of(),false),false,snapshot.get("revision").asLong(),"settings","request").status());
        assertEquals("Restored Gateway",source.store.transaction(EnterpriseConformanceTest.TENANT,false,tx->new ServerSettingsService(null,codec,ClientCheckInPolicy.QUICKSTART).current(tx).gateway().gatewayName()));
        var threshold=exported();((ObjectNode)threshold.get("settings").get("configuration")).put("requiredReviews",0);
        var refused=assertThrows(Failure.class,()->source.directory.importConfig(source.administrator,request(threshold,"MERGE",true,Map.of(),false),false,threshold.get("revision").asLong(),"threshold","request"));assertEquals(ControlErrorDetailCode.REQUIRED_REVIEWS_CHANGE,refused.detail());
        demote();var scoped=exported();assertFalse(scoped.has("settings"));
        assertEquals(403,assertThrows(Failure.class,()->source.directory.importConfig(source.administrator,request(snapshot,"MERGE",true,Map.of(),false),false,snapshot.get("revision").asLong(),"scoped","request")).status());
    }
    void demote(){source.retainIndependentAdministrator();source.store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var r=codec.model(entries.get(Kind.ROLE.id("recovery-role")).document(),ControlRole.class);var e=codec.entry(Kind.ROLE,codec.json(new ControlRole(r.id(),r.name(),true,r.revision()+1,UserRole.ADMINISTRATOR,r.roleType(),r.managementRules().stream().map(rule->new EnterpriseManagementRule(rule.actions().stream().filter(p->!Set.of("server_configuration.read","device_configuration.read","server_configuration.update","device_configuration.update","workload_binding.rotate_credential","user.disable").contains(p)).toList(),rule.groupType(),rule.groups(),rule.grantableScopes(),rule.conditions(),rule.deviceScope())).toList())));entries.put(e.id(),e);tx.save(before,new Directory(before.revision()+1,entries));return null;});}
    @Test void credentialRotationRebindsDelegatedBindingAndRequiresFeaturePermission(){
        String hash="c".repeat(64);var body=codec.json(Map.of("credentialSha256",hash,"enable",true));
        var result=codec.model(source.directory.rotateCredential(source.administrator,"workload",body,"rotate","request").body(),ControlWorkloadCredentialResult.class);
        assertEquals(2L,result.binding().credentialEpoch());assertEquals(hash,result.binding().credentialSha256());assertTrue(result.binding().enabled());assertEquals(1L,result.binding().delegatedSessionEpoch());assertNull(result.binding().needsReauthorization());
        var context=result.context();assertEquals(RequestMode.DELEGATED,context.mode());assertEquals("alice",context.userId());assertEquals(1L,context.sessionEpoch());assertEquals(2L,context.credentialEpoch());assertEquals("binding-a",context.bindingId());assertEquals(hash,context.credentialSha256());assertEquals("workload",context.workloadBindingId());
        assertEquals(409,assertThrows(Failure.class,()->source.directory.rotateCredential(source.administrator,"workload",body,"reuse","request")).status());
        // Disabling and re-enabling the user advances its session epoch; rotation rebinds the delegated binding to it.
        source.directory.userEnabled(source.administrator,"alice",false,"disable","request");source.directory.userEnabled(source.administrator,"alice",true,"enable","request");
        var rebound=codec.model(source.directory.rotateCredential(source.administrator,"workload",codec.json(Map.of("credentialSha256","d".repeat(64),"enable",true)),"rebind","request").body(),ControlWorkloadCredentialResult.class);
        assertEquals(3L,rebound.binding().delegatedSessionEpoch());assertEquals(3L,rebound.context().sessionEpoch());assertTrue(rebound.binding().credentialEpoch()>2L);
        demote();assertEquals(403,assertThrows(Failure.class,()->source.directory.rotateCredential(source.administrator,"workload",codec.json(Map.of("credentialSha256","e".repeat(64),"enable",true)),"scoped","request")).status());
        assertEquals(403,assertThrows(Failure.class,()->source.directory.userEnabled(source.administrator,"bob",false,"scoped-disable","request")).status());
    }
    @Test void onlyListedClaimsOfTheDelegatedUserReachMatchingTools(){
        var graph=source.graph;graph.add(Kind.USER,new ControlUser("alice","Alice",true,1L,null,null,Map.of("employee_id",new TextNode("E-101"),"cost_center",new TextNode("CC-7"))));
        var w=codec.model(graph.entries.get(Kind.WORKLOAD_BINDING.id("workload")).document(),ControlWorkloadBinding.class);
        graph.add(Kind.WORKLOAD_BINDING,new ControlWorkloadBinding(w.id(),w.name(),true,1L,w.agentId(),w.mode(),w.issuer(),w.subject(),w.audience(),w.credentialSha256(),w.credentialEpoch(),w.expiresAtUnixMs(),w.delegatedUserId(),w.parentBindingId(),w.delegatedSessionEpoch(),Map.of("employee_id",(com.fasterxml.jackson.databind.JsonNode)codec.value("[\"hr.*\"]")),null));
        var context=graph.context(RequestMode.DELEGATED,"binding-a");
        assertEquals("{\"employee_id\":\"E-101\"}",codec.json(ServerPackages.passedClaims(codec,graph.directory(),context,"hr/my-leave","hr.my-leave")));
        assertEquals("{\"employee_id\":\"E-101\"}",codec.json(ServerPackages.passedClaims(codec,graph.directory(),context,"hr.request-leave",null)));
        assertEquals("{}",codec.json(ServerPackages.passedClaims(codec,graph.directory(),context,"loans/evaluate","loans.evaluate")));
        assertEquals("{}",codec.json(ServerPackages.passedClaims(codec,graph.directory(),graph.context(RequestMode.SERVICE,"binding-a"),"hr.my-leave",null)));
    }
}
