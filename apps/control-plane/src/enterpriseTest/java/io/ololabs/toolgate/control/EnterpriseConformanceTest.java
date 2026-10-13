// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Normative vectors for complete group witnesses; no legacy ACL fixture authorizes these tests. */
class EnterpriseConformanceTest {
    static final Ids.TenantId TENANT=new Ids.TenantId("enterprise");
    static final long NOW=1791450000000L;
    /** Enrolls an online endpoint for a directory device, so Control can place calls on it. */
    static void online(PostgresStore store,Codec codec,String id){endpoint(store,codec,id,NOW);}
    static void endpoint(PostgresStore store,Codec codec,String id,long lastSeen){
        store.transaction(TENANT,true,tx->{tx.saveEndpoint(new Store.EndpointRecord(id,DirectoryService.digest(id),codec.json(new EndpointDeviceRecord(id,TENANT.value(),"bob",DirectoryService.digest(id),EndpointState.ACTIVE,1L,lastSeen,1L,null,null,null,null,null,null)),"public","0".repeat(64),"{}"));return null;});
    }
    static final String DIGEST="a".repeat(64),PROOF="b".repeat(64);
    final ContractCodec codec=new ContractCodec();
    Map<Ids.RecordId,Directory.Entry> entries;
    @TempDir java.nio.file.Path temp;
    @BeforeEach void graph() {
        entries=new HashMap<>();GroupGraph.defaults(entries,codec);
        add(Kind.USER,new ControlUser("alice","Alice",true,1L));add(Kind.USER,new ControlUser("bob","Bob",true,1L));add(Kind.USER,new ControlUser("admin","Administrator",true,1L));
        add(Kind.TEAM,new ControlTeam("team-a","Team A",true,1L,List.of("alice"),List.of()));
        add(Kind.TEAM,new ControlTeam("team-b","Team B",true,1L,List.of("alice"),List.of()));
        GroupGraph.addDefault(entries,codec,Kind.USER,"bob");GroupGraph.addDefault(entries,codec,Kind.USER,"admin");
        add(Kind.IDENTITY_BINDING,new ControlIdentityBinding("identity-alice","Alice identity",true,1L,"alice","https://identity.example","alice-subject",1L,NOW-1000,NOW,1L,"REVIEWED",0L));
        // Owners are deliberately different from the executing human.
        add(Kind.AGENT,new ControlAgent("agent","Agent",null,true,1L,"bob"));
        add(Kind.AGENT_GROUP,new ControlAgentGroup("group-a","Group A",null,true,1L,List.of("agent"),List.of()));
        add(Kind.AGENT_GROUP,new ControlAgentGroup("group-b","Group B",null,true,1L,List.of("agent"),List.of()));
        add(Kind.DEVICE,new ControlDevice("device","Device",true,1L,"bob"));
        add(Kind.DEVICE_GROUP,new ControlDeviceGroup("devices-a","Devices A",true,1L,List.of("device")));
        add(Kind.DEVICE_GROUP,new ControlDeviceGroup("devices-b","Devices B",true,1L,List.of("device")));
        add(Kind.DEVICE_EVIDENCE,new ControlDeviceEvidence("evidence","Verified device evidence",true,1L,"device",List.of("managed"),"in","10.1.2.3",NOW-1000,NOW+1000000));
        add(Kind.EXTRACTOR,new ControlResourceExtractor("extractor","Reviewed extractor",true,1L,EnterpriseExtractorKind.FIELDS,"1.0.0",List.of(new ExtractorField("/path",ResourceKind.FILE,false)),List.of(),32L,null,null));
        var definition=codec.model("{\"id\":\"tool\",\"name\":\"Tool\",\"description\":\"Untrusted description\",\"actions\":[{\"name\":\"read\",\"resourceKinds\":[\"FILE\"]},{\"name\":\"write\",\"resourceKinds\":[\"FILE\"]}],\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":{\"type\":\"object\"}}",ToolDefinition.class);
        add(Kind.TOOL,new ControlTool("tool","Tool",true,1L,definition,"extractor","1.0.0",DIGEST));
        add(Kind.TOOL_GROUP,new ControlToolGroup("tools-a","Tools A",true,1L,List.of("tool")));
        add(Kind.TOOL_GROUP,new ControlToolGroup("tools-b","Tools B",true,1L,List.of()));
        add(Kind.BINDING,new ControlExecutionBinding("binding-a","Binding A",true,1L,"tools-a","devices-a",List.of("read","write"),List.of(DIGEST),true,false));
        add(Kind.BINDING,new ControlExecutionBinding("binding-b","Binding B",true,1L,"tools-a","devices-b",List.of("read","write"),List.of(DIGEST),true,false));
        workload("workload","agent",RequestMode.DELEGATED,"alice",null);
        grant("human","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,scope("tools-a","devices-a"));
        grant("capability","group-a",EnterpriseSourceType.AGENT_GROUP,EnterpriseGrantPurpose.CAPABILITY,scope("tools-a","devices-a"));
        add(Kind.DELEGATION,new ControlDelegation("delegation","Delegation",true,1L,"team-a","group-a",scope("tools-a","devices-a")));
        directory().validate(512,1048576);codec.validatePolicies(directory());
    }
    Directory directory() {return new Directory(7,entries);}
    void add(Kind kind,Object model) {var e=codec.entry(kind,codec.json(model));entries.put(e.id(),e);}
    void remove(Kind kind,String id) {entries.remove(kind.id(id));}
    EnterpriseConditions conditions() {return new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),true,false,null,null);}
    EnterpriseScope scope(String tool,String device) {return new EnterpriseScope(new GroupSelection(List.of(tool),false),new GroupSelection(List.of(device),false),List.of("read","write"),false,List.of(new EnterpriseResourceRule(ResourceKind.FILE,"data",EnterpriseResourceMatch.PREFIX)),conditions());}
    void grant(String id,String source,EnterpriseSourceType type,EnterpriseGrantPurpose purpose,EnterpriseScope scope) {add(Kind.GRANT,new ControlAccessGrant(id,id,true,1L,type,source,purpose,scope));}
    void workload(String id,String agent,RequestMode mode,String user,String parent) {add(Kind.WORKLOAD_BINDING,new ControlWorkloadBinding(id,id,true,1L,agent,mode,"gateway","workload-subject","gateway",id.equals("workload")?PROOF:DirectoryService.digest(id),1L,NOW+100000,user,parent,user==null?null:1L,null,null));}
    EnterpriseContext context(RequestMode mode,String binding) {return new EnterpriseContext("request",TENANT.value(),mode,mode==RequestMode.SERVICE?null:"alice",mode==RequestMode.HUMAN?null:"agent",mode==RequestMode.HUMAN?null:"workload",List.of(),mode==RequestMode.SERVICE?null:1L,mode==RequestMode.HUMAN?null:1L,binding,"device");}
    EnterpriseEvaluation input(EnterpriseContext context,List<ResourceDescriptor> resources) {var tool=codec.model(entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);return new EnterpriseEvaluation(context,"tool","read",DIGEST,resources,new EnterpriseEvaluator(codec).toolDigest(directory(),tool),DIGEST,NOW,7L,true,null,null);}
    EnterpriseDecision decide(EnterpriseContext context) {return new EnterpriseEvaluator(codec).evaluate(TENANT,directory(),input(context,List.of(new ResourceDescriptor(ResourceKind.FILE,"data/report.txt"))));}
    void policy(String id,Decision decision) {add(Kind.POLICY,new ControlPolicy(id,id,true,1L,decision,scope("tools-a","devices-a"),new GroupSelection(List.of(),true),new GroupSelection(List.of(),true),new GroupSelection(List.of("team-b"),false)));}

    @Test void completeDelegatedPathAllowsWithoutOwnerEquality() {var decision=decide(context(RequestMode.DELEGATED,"binding-a"));assertEquals(Decision.ALLOW,decision.decision());assertEquals("team-a",decision.witnesses().getFirst().teamId());assertEquals("group-a",decision.witnesses().getFirst().agentGroupId());}
    @Test void humanExecutionRequiresOnlyHumanGrantAndBinding() {remove(Kind.DELEGATION,"delegation");remove(Kind.GRANT,"capability");assertEquals(Decision.ALLOW,decide(context(RequestMode.HUMAN,"binding-a")).decision());}
    @Test void defaultMembershipCreatesNoGrant() {remove(Kind.GRANT,"human");assertEquals(Decision.BLOCK,decide(context(RequestMode.DELEGATED,"binding-a")).decision());}
    @Test void unrelatedTeamsCannotShareGrantAndDelegation() {add(Kind.DELEGATION,new ControlDelegation("delegation","Delegation",true,2L,"team-b","group-a",scope("tools-a","devices-a")));assertEquals(EnterpriseDecisionReason.NO_GRANT,decide(context(RequestMode.DELEGATED,"binding-a")).reason());}
    @Test void unrelatedAgentGroupsCannotShareCapabilityAndDelegation() {add(Kind.DELEGATION,new ControlDelegation("delegation","Delegation",true,2L,"team-a","group-b",scope("tools-a","devices-a")));assertEquals(EnterpriseDecisionReason.NO_GRANT,decide(context(RequestMode.DELEGATED,"binding-a")).reason());}
    @Test void overlappingDeviceGroupsDoNotBridgeBinding() {assertEquals(EnterpriseDecisionReason.NO_GRANT,decide(context(RequestMode.DELEGATED,"binding-b")).reason());}
    @Test void unrelatedToolDeviceGrantsAreNotMultiplied() {grant("other","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,scope("tools-b","devices-b"));assertEquals(Decision.BLOCK,decide(context(RequestMode.HUMAN,"binding-b")).decision());}
    @Test void multipleExplicitBindingsRemainIndependent() {grant("second","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,scope("tools-a","devices-b"));assertEquals(Decision.ALLOW,decide(context(RequestMode.HUMAN,"binding-b")).decision());assertEquals("binding-b",decide(context(RequestMode.HUMAN,"binding-b")).witnesses().getFirst().bindingId());}
    @Test void allowPolicyDoesNotCreateMissingGrant() {remove(Kind.GRANT,"human");policy("allow",Decision.ALLOW);assertEquals(Decision.BLOCK,decide(context(RequestMode.HUMAN,"binding-a")).decision());}
    @Test void blockOverridesEveryGrant() {policy("block",Decision.BLOCK);policy("allow",Decision.ALLOW);assertEquals(EnterpriseDecisionReason.BLOCKED,decide(context(RequestMode.DELEGATED,"binding-a")).reason());}
    @Test void askObligationsAccumulateAcrossAllowPaths() {policy("ask-a",Decision.ASK);policy("ask-b",Decision.ASK);policy("allow",Decision.ALLOW);var d=decide(context(RequestMode.DELEGATED,"binding-a"));assertEquals(Decision.ASK,d.decision());assertEquals(List.of("ask-a","ask-b"),d.obligations());}
    @Test void disabledIdentityCannotBeApproved() {policy("ask",Decision.ASK);add(Kind.USER,new ControlUser("alice","Alice",false,2L));assertEquals(EnterpriseDecisionReason.IDENTITY_DISABLED,decide(context(RequestMode.DELEGATED,"binding-a")).reason());}
    @Test void missingRequiredTrustEvidenceDeniesButUnconditionalGrantNeedsNoUnusedAttestation() {
        remove(Kind.DEVICE_EVIDENCE,"evidence");assertEquals(Decision.ALLOW,decide(context(RequestMode.HUMAN,"binding-a")).decision());
        var ordinary=scope("tools-a","devices-a");
        var required=new EnterpriseConditions(0L,0L,List.of(),List.of("managed"),List.of(),List.of(),true,false,null,null);
        grant("human","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,new EnterpriseScope(ordinary.toolGroups(),ordinary.deviceGroups(),ordinary.actions(),ordinary.allActions(),ordinary.resources(),required));
        assertEquals(EnterpriseDecisionReason.DEVICE_UNTRUSTED,decide(context(RequestMode.HUMAN,"binding-a")).reason());
    }
    @Test void serviceModeNeedsExplicitServiceGrantAndCapability() {workload("workload","agent",RequestMode.SERVICE,null,null);assertEquals(Decision.BLOCK,decide(context(RequestMode.SERVICE,"binding-a")).decision());grant("service","group-a",EnterpriseSourceType.AGENT_GROUP,EnterpriseGrantPurpose.SERVICE,scope("tools-a","devices-a"));assertEquals(Decision.ALLOW,decide(context(RequestMode.SERVICE,"binding-a")).decision());remove(Kind.GRANT,"capability");assertEquals(Decision.BLOCK,decide(context(RequestMode.SERVICE,"binding-a")).decision());}
    @Test void serviceIdentityCannotReuseDelegatedCredential() {assertEquals(EnterpriseDecisionReason.INVALID_CONTEXT,decide(context(RequestMode.SERVICE,"binding-a")).reason());}
    @Test void revokedSessionDenies() {var c=context(RequestMode.HUMAN,"binding-a");c=new EnterpriseContext(c.requestId(),c.tenantId(),c.mode(),c.userId(),null,null,List.of(),2L,null,c.bindingId(),c.deviceId());assertEquals(EnterpriseDecisionReason.IDENTITY_DISABLED,decide(c).reason());}
    @Test void delegatedCredentialCannotAttachToNewHumanSession() {
        add(Kind.IDENTITY_BINDING,new ControlIdentityBinding("identity-alice","Alice identity",true,2L,"alice","https://identity.example","alice-subject",2L,NOW-1000,NOW,1L,"REVIEWED",NOW));
        var c=context(RequestMode.DELEGATED,"binding-a");c=new EnterpriseContext(c.requestId(),c.tenantId(),c.mode(),c.userId(),c.agentId(),c.workloadBindingId(),List.of(),2L,c.credentialEpoch(),c.bindingId(),c.deviceId());
        assertEquals(EnterpriseDecisionReason.IDENTITY_DISABLED,decide(c).reason());
    }
    @Test void olderTokenWithoutEpochCannotSurviveUserReenablement() {
        var store=new PostgresStore(SqliteState.open(temp.resolve("session.sqlite")),codec);
        add(Kind.IDENTITY_BINDING,new ControlIdentityBinding("identity-alice","Alice identity",true,2L,"alice","https://identity.example","alice-subject",2L,NOW-1000,NOW,1L,"REVIEWED",NOW));
        store.transaction(TENANT,true,tx->{tx.save(tx.load(),directory());return null;});var intake=new IdentityIntake(store,codec);
        assertThrows(Failure.class,()->intake.observe(TENANT,"https://identity.example","alice-subject",null,null,NOW-1000,NOW+60000,NOW));
        assertTrue(intake.observe(TENANT,"https://identity.example","alice-subject",null,null,NOW,NOW+60000,NOW).enabled());
    }
    @Test void crossTenantCannotUseSameIdentifiers() {var d=new EnterpriseEvaluator(codec).evaluate(new Ids.TenantId("other"),directory(),input(context(RequestMode.HUMAN,"binding-a"),List.of(new ResourceDescriptor(ResourceKind.FILE,"data/report.txt"))));assertEquals(EnterpriseDecisionReason.INVALID_CONTEXT,d.reason());}
    @Test void everyAffectedResourceNeedsCompletePermission() {var r=input(context(RequestMode.HUMAN,"binding-a"),List.of(new ResourceDescriptor(ResourceKind.FILE,"data/source.txt"),new ResourceDescriptor(ResourceKind.FILE,"private/destination.txt")));assertEquals(Decision.BLOCK,new EnterpriseEvaluator(codec).evaluate(TENANT,directory(),r).decision());}
    @Test void resourcePrefixRespectsSegmentBoundary() {assertFalse(EnterpriseEvaluator.resource(new EnterpriseResourceRule(ResourceKind.FILE,"data",EnterpriseResourceMatch.PREFIX),new ResourceDescriptor(ResourceKind.FILE,"database/private.txt")));}
    @Test void emptySelectorsDoNotMeanWildcard() {var s=scope("tools-a","devices-a");grant("human","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,new EnterpriseScope(new GroupSelection(List.of(),false),s.deviceGroups(),s.actions(),s.allActions(),s.resources(),s.conditions()));assertEquals(Decision.BLOCK,decide(context(RequestMode.HUMAN,"binding-a")).decision());}
    @Test void numericNetworkChecksNeverResolveHostNames() {assertTrue(NetworkRange.parse("10.0.0.0/8").contains("10.1.2.3"));assertFalse(NetworkRange.parse("10.0.0.0/8").contains("example.test"));assertThrows(IllegalArgumentException.class,()->NetworkRange.parse("example.test/8"));}
    @Test void sourceAndDestinationAndAllBatchMembersAreExtracted() {
        var extractor=new ControlResourceExtractor("batch","Batch",true,1L,EnterpriseExtractorKind.FILESYSTEM,"1.0.0",List.of(new ExtractorField("/sources",ResourceKind.FILE,true),new ExtractorField("/destination",ResourceKind.FILE,false)),List.of(),32L,null,null);
        var request=codec.model("{\"toolId\":\"tool\",\"action\":\"read\",\"arguments\":{\"sources\":[\"data/a\",\"data/b\"],\"destination\":\"data/c\"}}",AuthorizationRequest.class);
        assertEquals(3,new ResourceExtraction(codec).extract(extractor,request).resources().size());
    }
    @Test void filesystemAndUrlEscapesRejectBeforePermissionEvaluation() {for(var path:List.of("../outside","data/../outside","C:/outside","data\\outside","data/%2e%2e/outside","data/NUL"))assertThrows(Failure.class,()->ResourceExtraction.normalize(ResourceKind.FILE,path));assertThrows(Failure.class,()->ResourceExtraction.normalize(ResourceKind.URL,"https://example.test/a/../private"));assertThrows(Failure.class,()->ResourceExtraction.normalize(ResourceKind.URL,"https://user:pass@example.test/private"));}
    @Test void mandatoryMembershipAndPrimaryToolGroupAreValidated() {GroupGraph.removeMember(entries,codec,Kind.AGENT,"agent");assertThrows(IllegalArgumentException.class,()->codec.validatePolicies(directory()));graph();add(Kind.TOOL_GROUP,new ControlToolGroup("tools-b","Tools B",true,2L,List.of("tool")));assertThrows(IllegalArgumentException.class,()->codec.validatePolicies(directory()));}
    @Test void slugsAreUniquePerKindAndFollowTheRoutePattern() {
        add(Kind.AGENT,new ControlAgent("agent","Agent","finance-bot",true,2L,"bob"));add(Kind.AGENT_GROUP,new ControlAgentGroup("group-a","Group A","finance-bot",true,2L,List.of("agent"),List.of()));
        codec.validatePolicies(directory());
        add(Kind.AGENT_GROUP,new ControlAgentGroup("group-b","Group B","finance-bot",true,2L,List.of("agent"),List.of()));
        assertThrows(IllegalArgumentException.class,()->codec.validatePolicies(directory()));
        assertThrows(RuntimeException.class,()->codec.entry(Kind.AGENT,codec.json(new ControlAgent("agent","Agent","Finance_Bot",true,3L,"bob"))));
    }
    @Test void groupRoleTypeCannotBeMisassigned() {add(Kind.ROLE,new ControlRole("human-role","Human role",true,1L,UserRole.BASIC,EnterpriseRoleType.HUMAN,List.of()));add(Kind.AGENT_GROUP,new ControlAgentGroup("group-a","Group A",null,true,2L,List.of("agent"),List.of("human-role")));assertThrows(IllegalArgumentException.class,()->codec.validatePolicies(directory()));}
    @Test void managementRoleCannotBeRuntimeGrantSource() {add(Kind.ROLE,new ControlRole("management","Management",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,List.of()));grant("human","management",EnterpriseSourceType.ROLE,EnterpriseGrantPurpose.HUMAN,scope("tools-a","devices-a"));assertThrows(IllegalArgumentException.class,()->codec.validatePolicies(directory()));}
    @Test void deterministicDecisionDoesNotDependOnMapIterationOrder() {var expected=codec.json(decide(context(RequestMode.DELEGATED,"binding-a")));var reverse=new ArrayList<>(entries.entrySet());Collections.reverse(reverse);entries=new LinkedHashMap<>();for(var e:reverse)entries.put(e.getKey(),e.getValue());assertEquals(expected,codec.json(decide(context(RequestMode.DELEGATED,"binding-a"))));}
    @Test void legacyIndividualAssignmentsAreRejected() {assertThrows(Failure.class,()->codec.entry(Kind.USER,"{\"id\":\"user\",\"name\":\"User\",\"enabled\":true,\"revision\":1,\"access\":{\"role\":\"SUPER_ADMIN\"}}"));assertThrows(Failure.class,()->codec.entry(Kind.AGENT,"{\"id\":\"agent\",\"name\":\"Agent\",\"enabled\":true,\"revision\":1,\"ownerUserId\":\"alice\",\"allowedToolIds\":[]}"));}
    @Test void concurrentVerifiedIntakeCreatesOneDisabledIdentityWithMembership() throws Exception {
        var source=SqliteState.open(temp.resolve("intake.sqlite"));var store=new PostgresStore(source,codec);var intake=new IdentityIntake(store,codec);var executor=Executors.newFixedThreadPool(4);
        try {var futures=new ArrayList<Future<IdentityIntake.Identity>>();for(int i=0;i<4;i++)futures.add(executor.submit(()->intake.observe(TENANT,"https://identity.example","new-subject",null,null,NOW-1000,NOW+60000,NOW)));var ids=new HashSet<String>();for(var f:futures){var identity=f.get();assertFalse(identity.enabled());ids.add(identity.userId());}assertEquals(1,ids.size());store.transaction(TENANT,false,tx->{var d=tx.load();assertEquals(1,d.entries().values().stream().filter(e->e.id().kind()==Kind.USER).count());assertEquals(List.of("team-default"),GroupGraph.memberships(d,codec,Kind.USER,ids.iterator().next(),false));return null;});}finally{executor.shutdownNow();}
    }
    @Test void invalidAuthenticationDoesNotCreateUsers() {var source=SqliteState.open(temp.resolve("invalid.sqlite"));var store=new PostgresStore(source,codec);assertThrows(Failure.class,()->new IdentityIntake(store,codec).observe(TENANT,"https://identity.example","subject",null,null,NOW-1000,NOW-1,NOW));int size=store.transaction(TENANT,false,tx->tx.load().entries().size());assertEquals(0,size);}
}
