// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseConfigurationTest {
    @TempDir java.nio.file.Path temp;EnterpriseAdministrationTest fixture;ConfigurationChanges changes;DirectoryService.Actor requester,checker;
    @BeforeEach void setup(){fixture=new EnterpriseAdministrationTest();fixture.temp=temp;fixture.seedReviewedRecovery();fixture.retainIndependentAdministrator();var codec=fixture.graph.codec;
        fixture.store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var r=codec.model(entries.get(Ids.Kind.ROLE.id("recovery-role")).document(),ControlRole.class);var rules=r.managementRules().stream().map(rule->{var actions=new ArrayList<>(rule.actions());if(!actions.contains("configuration_change.approve"))actions.add("configuration_change.approve");return new EnterpriseManagementRule(actions,rule.groupType(),rule.groups(),rule.grantableScopes(),rule.conditions(),null);}).toList();entries.put(Ids.Kind.ROLE.id(r.id()),codec.entry(Ids.Kind.ROLE,codec.json(new ControlRole(r.id(),r.name(),true,2L,UserRole.ADMINISTRATOR,r.roleType(),rules))));entries.put(Ids.Kind.TEAM.id("recovery-team"),codec.entry(Ids.Kind.TEAM,codec.json(new ControlTeam("recovery-team","Reviewers",true,2L,List.of("admin","alice","bob"),List.of("recovery-role")))));tx.save(before,new Directory(before.revision()+1,entries));return null;});
        requester=fixture.directory.portalActor(EnterpriseConformanceTest.TENANT,"a".repeat(64),"admin");checker=fixture.directory.portalActor(EnterpriseConformanceTest.TENANT,"b".repeat(64),"alice");changes=new ConfigurationChanges(fixture.store,codec,Clock.systemUTC(),512,1048576);
    }
    void reviews(int required){fixture.store.transaction(requester.tenant(),true,tx->{var current=new ServerSettingsService(null,fixture.graph.codec,ClientCheckInPolicy.QUICKSTART).current(tx);tx.saveServerSettings(current.revision()+1,fixture.graph.codec.json(new io.ololabs.toolgate.contracts.v2.ControlServerSettings(2L,current.revision()+1,current.gateway(),current.deviceApproval(),current.clientCheckIn(),new io.ololabs.toolgate.contracts.v2.ConfigurationSettings((long)required))));return null;});}
    ServerSettingsService settings(){return new ServerSettingsService(fixture.store,fixture.graph.codec,ClientCheckInPolicy.QUICKSTART).reviews(changes);}
    @Test void thresholdIsCapturedWhenTheChangeIsCreated(){
        reviews(1);var first=propose();assertEquals(1L,first.requiredReviews());reviews(2);
        var stored=fixture.graph.codec.model(changes.get(requester,first.id()).body(),EnterpriseConfigurationChange.class);assertEquals(1L,stored.requiredReviews());
        var second=fixture.graph.codec.model(changes.create(requester,new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.CREATE,ControlEntityKind.USER,"later-user",fixture.graph.codec.json(new ControlUser("later-user","Later",false,1L)),0L),"later-key","request").body(),EnterpriseConfigurationChange.class);assertEquals(2L,second.requiredReviews());
        var c=transition(requester,first,"SUBMIT");c=transition(checker,c,"APPROVE");assertEquals(EnterpriseConfigurationState.APPROVED,c.state());
    }
    @Test void zeroReviewsAppliesAtOnceAndIsAudited(){
        reviews(0);var command=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.CREATE,ControlEntityKind.USER,"direct-user",fixture.graph.codec.json(new ControlUser("direct-user","Direct",false,1L)),0L);
        assertEquals(201,changes.mutate(requester,command,"zero-review","request").status());
        assertEquals("{\"requiredReviews\":0}",settings().reviewPolicy(checker).body());
        fixture.store.transaction(requester.tenant(),false,tx->{assertTrue(tx.load().entries().containsKey(Ids.Kind.USER.id("direct-user")));assertTrue(tx.auditPage(0,100).contains("CONFIGURATION_DIRECT_NO_REVIEW"));return null;});
    }
    @Test void settingsUseFeaturePermissionsAndThresholdChangesNeedCurrentReviewsWithoutBypass(){
        assertEquals(200,settings().get(requester).status());
        superAdministrator("admin");var reply=settings().patch(requester,"{\"configuration\":{\"requiredReviews\":0}}",1,"threshold","request");assertEquals(202,reply.status());
        var c=fixture.graph.codec.model(reply.body(),EnterpriseConfigurationChange.class);assertEquals(ControlEntityKind.SETTINGS,c.command().kind());assertEquals(2L,c.requiredReviews());assertEquals(2,(int)fixture.store.transaction(requester.tenant(),false,tx->ServerSettingsService.requiredReviews(tx,fixture.graph.codec)));
        c=transition(requester,c,"SUBMIT");var submitted=c;
        // The requester's Super Admin role neither reviews its own threshold change nor satisfies the quorum.
        assertEquals(403,assertThrows(Failure.class,()->transition(requester,submitted,"APPROVE")).status());
        c=transition(checker,c,"APPROVE");assertEquals(EnterpriseConfigurationState.PENDING,c.state());var once=c;assertEquals(403,assertThrows(Failure.class,()->transition(requester,once,"APPLY")).status());
        var bob=fixture.directory.portalActor(EnterpriseConformanceTest.TENANT,"c".repeat(64),"bob");c=transition(bob,c,"APPROVE");assertEquals(EnterpriseConfigurationState.APPROVED,c.state());
        c=transition(requester,c,"APPLY");assertEquals(EnterpriseConfigurationState.APPLIED,c.state());
        assertEquals(0L,fixture.graph.codec.model(settings().get(requester).body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class).configuration().requiredReviews());
        // At 0 a Super Admin changes the threshold directly.
        var direct=settings().patch(requester,"{\"configuration\":{\"requiredReviews\":1}}",2,"threshold-direct","request");assertEquals(200,direct.status());
    }
    EnterpriseConfigurationChange propose(){var c=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.CREATE,ControlEntityKind.USER,"pending-user",fixture.graph.codec.json(new ControlUser("pending-user","Pending",false,1L)),0L);return fixture.graph.codec.model(changes.create(requester,c,"proposal-key","request").body(),EnterpriseConfigurationChange.class);}
    EnterpriseConfigurationChange transition(DirectoryService.Actor actor,EnterpriseConfigurationChange c,String action){return fixture.graph.codec.model(changes.transition(actor,c.id(),fixture.graph.codec.json(new EnterpriseConfigurationTransition(c.revision(),EnterpriseConfigurationAction.valueOf(action))),UUID.randomUUID().toString(),"request").body(),EnterpriseConfigurationChange.class);}
    void superAdministrator(String user){fixture.store.transaction(requester.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var codec=fixture.graph.codec;entries.put(Ids.Kind.ROLE.id("super-role"),codec.entry(Ids.Kind.ROLE,codec.json(new ControlRole("super-role","Super Admin",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of()))));entries.put(Ids.Kind.TEAM.id("super-team"),codec.entry(Ids.Kind.TEAM,codec.json(new ControlTeam("super-team","Installation owners",true,1L,List.of(user),List.of("super-role")))));tx.save(before,new Directory(before.revision()+1,entries));return null;});}
    @Test void superAdministratorCanApproveOwnRequestAndSatisfyTwoReviewQuorum(){
        superAdministrator("admin");var command=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.CREATE,ControlEntityKind.ROLE,"new-human-role",fixture.graph.codec.json(new ControlRole("new-human-role","New role",true,1L,UserRole.BASIC,EnterpriseRoleType.HUMAN,List.of())),0L);
        var c=fixture.graph.codec.model(changes.create(requester,command,"super-proposal","request").body(),EnterpriseConfigurationChange.class);assertEquals(2L,c.requiredReviews());c=transition(requester,c,"SUBMIT");c=transition(requester,c,"APPROVE");assertEquals(EnterpriseConfigurationState.APPROVED,c.state());assertEquals(1,c.reviews().size());c=transition(requester,c,"APPLY");assertEquals(EnterpriseConfigurationState.APPLIED,c.state());fixture.store.transaction(requester.tenant(),false,tx->{assertTrue(tx.auditPage(0,100).contains("CONFIGURATION_APPROVE_SUPER_ADMIN"));return null;});
    }
    @Test void superAdministratorCanSubmitApproveAndApplyAnotherUsersDraft(){superAdministrator("alice");var c=transition(checker,propose(),"SUBMIT");c=transition(checker,c,"APPROVE");c=transition(checker,c,"APPLY");assertEquals(EnterpriseConfigurationState.APPLIED,c.state());}
    @Test void cachedSuperFlagDoesNotAllowScopedRequesterToApproveOwnRequest(){var c=transition(requester,propose(),"SUBMIT");var forged=new DirectoryService.Actor(requester.tenant(),requester.id(),true,true,requester.userId());assertEquals(403,assertThrows(Failure.class,()->transition(forged,c,"APPROVE")).status());}
    @Test void superAdministratorCannotApproveExpiredRequest(){superAdministrator("admin");var c=transition(requester,propose(),"SUBMIT");var later=new ConfigurationChanges(fixture.store,fixture.graph.codec,Clock.fixed(Instant.ofEpochMilli(c.expiresAtUnixMs()+1),ZoneOffset.UTC),512,1048576);assertEquals(409,assertThrows(Failure.class,()->later.transition(requester,c.id(),fixture.graph.codec.json(new EnterpriseConfigurationTransition(c.revision(),EnterpriseConfigurationAction.APPROVE)),"expired-super","request")).status());}
    @Test void directSuperAdministratorCrudIsAuditedAndRequiresFreshIdentifiersAfterDeletion(){
        superAdministrator("admin");var codec=fixture.graph.codec;var create=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.CREATE,ControlEntityKind.USER,"direct-user",codec.json(new ControlUser("direct-user","New user",false,1L)),0L);assertEquals(201,changes.mutate(requester,create,"direct-create","request").status());
        var update=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.UPDATE,ControlEntityKind.USER,"direct-user",codec.json(new ControlUser("direct-user","New user",true,1L)),1L);assertEquals(200,changes.mutate(requester,update,"direct-update","request").status());
        var delete=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.DELETE,ControlEntityKind.USER,"direct-user","",2L);assertEquals(204,changes.mutate(requester,delete,"direct-delete","request").status());assertEquals(409,assertThrows(Failure.class,()->changes.mutate(requester,create,"direct-recreate","request")).status());fixture.store.transaction(requester.tenant(),false,tx->{assertFalse(tx.load().entries().containsKey(Ids.Kind.USER.id("direct-user")));assertTrue(tx.auditPage(0,100).contains("CONFIGURATION_DIRECT_SUPER_ADMIN"));return null;});
    }
    @Test void scopedAdministratorMutationCreatesDraftEvenWithCachedSuperFlag(){var forged=new DirectoryService.Actor(requester.tenant(),requester.id(),true,true,requester.userId());var command=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.CREATE,ControlEntityKind.USER,"scoped-user",fixture.graph.codec.json(new ControlUser("scoped-user","Pending",false,1L)),0L);assertEquals(202,changes.mutate(forged,command,"scoped-create","request").status());fixture.store.transaction(requester.tenant(),false,tx->{assertFalse(tx.load().entries().containsKey(Ids.Kind.USER.id("scoped-user")));return null;});}
    @Test void makerCheckerPreviewAndAtomicApply(){reviews(1);var c=propose();assertEquals(EnterpriseConfigurationState.DRAFT,c.state());assertTrue(c.affectedIndividuals().contains("USER:pending-user"));fixture.store.transaction(requester.tenant(),false,tx->{assertNull(tx.load().entries().get(Ids.Kind.USER.id("pending-user")));return null;});c=transition(requester,c,"SUBMIT");var pending=c;assertThrows(Failure.class,()->transition(requester,pending,"APPROVE"));c=transition(checker,c,"APPROVE");assertEquals(EnterpriseConfigurationState.APPROVED,c.state());c=transition(requester,c,"APPLY");assertEquals(EnterpriseConfigurationState.APPLIED,c.state());fixture.store.transaction(requester.tenant(),false,tx->{assertEquals(List.of("team-default"),GroupGraph.memberships(tx.load(),fixture.graph.codec,Ids.Kind.USER,"pending-user",false));assertTrue(tx.auditPage(0,100).contains("CONFIGURATION_APPLY"));return null;});assertEquals(c.id(),propose().id());}
    @Test void concurrentGraphChangeInvalidatesReview(){var c=transition(requester,propose(),"SUBMIT");fixture.directory.mutate(requester,Ids.Kind.USER,"other","CREATE",fixture.graph.codec.json(new ControlUser("other","Other",false,1L)),0,"other-key","request");assertEquals(409,assertThrows(Failure.class,()->transition(checker,c,"APPROVE")).status());}
    @Test void revokedReviewerCannotApplyOrReplay(){var c=transition(checker,transition(requester,propose(),"SUBMIT"),"APPROVE");fixture.store.transaction(requester.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());entries.put(Ids.Kind.TEAM.id("recovery-team"),fixture.graph.codec.entry(Ids.Kind.TEAM,fixture.graph.codec.json(new ControlTeam("recovery-team","Reviewers",true,3L,List.of("admin"),List.of("recovery-role")))));tx.save(before,new Directory(before.revision()+1,entries));return null;});assertThrows(Failure.class,()->transition(requester,c,"APPLY"));}
    @Test void cancellationNeverMutatesDirectory(){var c=transition(requester,propose(),"CANCEL");assertEquals(EnterpriseConfigurationState.CANCELLED,c.state());assertThrows(Failure.class,()->transition(requester,c,"SUBMIT"));}
    @Test void completeExportCanEnterReviewedImportWithoutRotatingUnchangedCredentials(){
        var codec=fixture.graph.codec;
        var snapshot=fixture.directory.export(requester,false);
        var document=codec.json(java.util.Map.of("snapshot",codec.value(snapshot.body().replace("Reviewers","Reviewed operators")),"mode","REPLACE","dryRun",false));
        var command=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.IMPORT,ControlEntityKind.USER,"configuration-import",document,snapshot.revision());
        var change=codec.model(changes.create(requester,command,"import-key","request").body(),EnterpriseConfigurationChange.class);
        assertEquals(2L,change.requiredReviews());assertFalse(change.impact().isEmpty());
    }
}
