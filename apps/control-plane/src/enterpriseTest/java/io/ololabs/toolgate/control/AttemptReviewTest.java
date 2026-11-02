// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Trusted storage-port fixtures; public current-authorization checks are tested separately. */
final class AttemptReviewTest {
    @TempDir Path temp;
    private EnterpriseOperationTest rig;
    private boolean postgres;
    @BeforeAll static void key()throws Exception{EnterpriseOperationTest.signingKey();}
    @BeforeEach void setup()throws Exception{
        rig=new EnterpriseOperationTest();rig.temp=Files.createDirectory(temp.resolve("review-"+UUID.randomUUID()));rig.setup();
        rig.graph.add(Kind.USER,new ControlUser("checker","Independent checker",true,1L));
        rig.graph.add(Kind.TEAM,new ControlTeam("team-b","Reviewers",true,2L,List.of("alice","admin","checker","bob"),List.of("reviewer-role")));
        rig.saveGraph();
        if(postgres){
            String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
            String database="attempt_review_"+UUID.randomUUID().toString().replace("-","");
            try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
            String url=base.replace("/control?","/"+database+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            rig.keyPolicySource=source;rig.store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
            EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
            rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
        }
    }
    record Fixture(EnterpriseInvocation invocation,EnterpriseReconciliation proposal) {}
    private Fixture fixture(boolean success,boolean pendingWrite,String maker){
        var i=rig.consume(rig.reserve(rig.submit("review-operation")));
        if(pendingWrite)rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.effects().authorize(new EffectStore.Binding("review-effect",i.id(),i.reservedNonce(),"route","sha256:"+"a".repeat(64),
                    "device","broker","handler","review-auth",rig.clock.millis()+10000,rig.clock.millis()+60000),rig.clock.millis());
            tx.effects().sending("review-auth","device","broker","handler",rig.clock.millis());return null;
        });
        var terminal=rig.graph.codec.model(rig.operations.report(rig.gateway.tenant(),"device",rig.graph.codec.json(
                new EnterpriseEffectReport(i.id(),i.revision(),success?EnterpriseInvocationState.SUCCEEDED:EnterpriseInvocationState.OUTCOME_UNKNOWN,
                        success?i.evaluation().resources():List.of(),success?EnterpriseConformanceTest.DIGEST:null))).body(),EnterpriseInvocation.class);
        var outcome=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome(i.id()));
        assertEquals(EnterpriseOperationState.OUTCOME_UNKNOWN,outcome.value().state());
        var attestation=new EnterpriseAttemptSettlementReview(outcome.value().revision(),List.of(i.reservedNonce()),true);
        var request=new EnterpriseReconciliationRequest(terminal.revision(),success?EnterpriseReconciledState.SUCCEEDED:EnterpriseReconciledState.FAILED,
                success?i.evaluation().resources():List.of(),"b".repeat(64),success?EnterpriseConformanceTest.DIGEST:null,attestation);
        var review=new EnterpriseReconciliation("attempt-review",i.id(),maker,request,DirectoryService.digest(rig.graph.codec.json(request)),
                1L,null,EnterpriseReconciliationState.PENDING);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().saveReconciliation(review,0);return null;});
        return new Fixture(terminal,review);
    }
    private EnterpriseReconciliation approved(Fixture f){
        var old=f.proposal();var next=new EnterpriseReconciliation(old.id(),old.invocationId(),old.requesterUserId(),old.request(),old.requestDigest(),
                2L,"checker",EnterpriseReconciliationState.APPLIED);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().saveReconciliation(next,1);return null;});
        return next;
    }
    private EnterpriseOperationOutcome apply(Fixture f){
        return rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.enterprise().recordAttemptReview(f.invocation().id(),f.invocation().reservedNonce(),f.proposal().id(),rig.clock.millis());
            return new OperationFinalizer(rig.graph.codec).refresh(tx,f.invocation().id(),rig.clock.millis());
        });
    }
    @Test void pendingProposalCannotSettleAndApprovedProofDoesNotRewriteExecutorState(){
        var f=fixture(true,false,"admin");
        assertThrows(Failure.class,()->apply(f));approved(f);var outcome=apply(f);
        assertEquals(EnterpriseOperationState.SUCCEEDED,outcome.state());assertEquals(EnterpriseOutcomeGrade.REVIEWER_CONFIRMED,outcome.grade());
        assertFalse(outcome.retryable());
        assertEquals(f.invocation(),rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(f.invocation().id())));
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attempts(f.invocation().id(),rig.clock.millis())).size());
        assertThrows(Failure.class,()->rig.reserve(f.invocation()));
    }
    @Test void reviewedUnknownAttemptSettlesWithoutTurningItsReportIntoSuccessOrFailure(){
        var f=fixture(false,false,"admin");approved(f);var outcome=apply(f);
        assertEquals(EnterpriseOperationState.FAILED,outcome.state());assertEquals(EnterpriseOutcomeGrade.REVIEWER_CONFIRMED,outcome.grade());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,rig.store.transaction(rig.gateway.tenant(),false,
                tx->tx.enterprise().invocation(f.invocation().id())).state());
        var facts=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().finalizationFacts(f.invocation().id(),rig.clock.millis()));
        assertEquals("OUTCOME_UNKNOWN",facts.attempts().getFirst().state());assertTrue(facts.attempts().getFirst().settled());
    }
    @Test void approvedHumanScopeCannotResolveASentAmbiguousGeneration(){
        var f=fixture(false,true,"admin");approved(f);assertThrows(Failure.class,()->apply(f));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptReview(f.invocation().id(),f.invocation().reservedNonce())));
    }
    @Test void newEvidenceInvalidatesTheReviewedOutcomeRevision(){
        var f=fixture(true,false,"admin");
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.enterprise().saveExecutionEvidence(f.invocation().id(),f.invocation().reservedNonce(),"c".repeat(64),"{\"kind\":\"spool-observation\"}",rig.clock.millis());
            new OperationFinalizer(rig.graph.codec).refresh(tx,f.invocation().id(),rig.clock.millis());return null;
        });
        approved(f);assertThrows(Failure.class,()->apply(f));
    }
    @Test void originalAgentOwnerCannotAcquireReviewIndependenceAfterTransfer(){
        var f=fixture(true,false,"bob");
        rig.graph.add(Kind.AGENT,new ControlAgent("agent","Transferred",null,true,2L,"admin"));rig.saveGraph();
        approved(f);assertThrows(Failure.class,()->apply(f));
        assertEquals(Set.of("alice","bob"),rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().reviewOwners(f.invocation().id())));
    }
    @Test void capturedReviewAndItsApprovedSourceCannotBeRewrittenOrDeleted()throws Exception{
        var f=fixture(true,false,"admin");approved(f);apply(f);
        for(String table:List.of("control_attempt_reviews","control_outcome_reconciliations","control_operation_review_owners")){
            try(var c=rig.keyPolicySource.getConnection()){
                c.setAutoCommit(false);
                for(String sql:List.of("UPDATE "+table+" SET document=document WHERE tenant_id=?","DELETE FROM "+table+" WHERE tenant_id=?")){
                    if(postgres)try(var set=c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){set.setString(1,rig.gateway.tenant().value());set.execute();}
                    try(var change=c.prepareStatement(sql)){change.setString(1,rig.gateway.tenant().value());assertThrows(java.sql.SQLException.class,change::executeUpdate);}
                    c.rollback();
                }
            }
        }
    }
    private DirectoryService.Actor checker(){
        return new DirectoryService.Actor(rig.gateway.tenant(),"f".repeat(64),true,false,"checker");
    }
    private EnterpriseReconciliation propose(Fixture f){
        return rig.graph.codec.model(rig.operations.proposeReconciliation(rig.reviewer,f.invocation().id(),
                rig.graph.codec.json(f.proposal().request()),"public-attempt-review").body(),EnterpriseReconciliation.class);
    }
    private String decision(EnterpriseReconciliation proposal,boolean approve){
        return rig.graph.codec.json(new EnterpriseReconciliationDecision(proposal.revision(),approve));
    }
    @Test void publicMakerCheckerSettlesOnceWithoutRewritingOrReissuing(){
        var f=fixture(true,false,"admin");var proposal=propose(f);
        assertEquals(proposal,propose(f));assertThrows(Failure.class,()->rig.operations.reconcile(rig.reviewer,proposal.id(),decision(proposal,true)));
        var applied=rig.graph.codec.model(rig.operations.reconcile(checker(),proposal.id(),decision(proposal,true)).body(),EnterpriseReconciliation.class);
        assertEquals(EnterpriseReconciliationState.APPLIED,applied.state());
        var view=rig.graph.codec.model(rig.operations.history(rig.reviewer,f.invocation().id(),null,10).body(),EnterpriseOperationHistoryPage.class);
        assertEquals(EnterpriseOperationState.SUCCEEDED,view.operation().state());assertEquals(EnterpriseOutcomeGrade.REVIEWER_CONFIRMED,view.operation().grade());
        assertEquals(EnterpriseAttemptEffectScope.REVIEWER_WITNESSED,view.attempts().getFirst().scope());assertTrue(view.attempts().getFirst().settled());
        assertEquals(f.invocation(),rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(f.invocation().id())));
        String audit=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,100));
        assertEquals(applied,rig.graph.codec.model(rig.operations.reconcile(checker(),proposal.id(),decision(proposal,true)).body(),EnterpriseReconciliation.class));
        assertEquals(audit,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,100)));
        assertThrows(Failure.class,()->rig.reserve(f.invocation()));
    }
    @Test void publicApprovalRejectsNewEvidenceButStaleDenialDoesNotSettleAnything(){
        var f=fixture(true,false,"admin");var proposal=propose(f);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.enterprise().saveExecutionEvidence(f.invocation().id(),f.invocation().reservedNonce(),"d".repeat(64),"{\"kind\":\"new-observation\"}",rig.clock.millis());
            new OperationFinalizer(rig.graph.codec).refresh(tx,f.invocation().id(),rig.clock.millis());return null;
        });
        assertEquals(409,assertThrows(Failure.class,()->rig.operations.reconcile(checker(),proposal.id(),decision(proposal,true))).status());
        assertEquals(EnterpriseReconciliationState.PENDING,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().reconciliation(proposal.id())).state());
        assertEquals(EnterpriseReconciliationState.DENIED,rig.graph.codec.model(rig.operations.reconcile(checker(),proposal.id(),decision(proposal,false)).body(),EnterpriseReconciliation.class).state());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptReview(f.invocation().id(),f.invocation().reservedNonce())));
    }
    @Test void bothMakersAndCheckersNeedCurrentReviewPermission(){
        var f=fixture(true,false,"admin");var proposal=propose(f);
        rig.graph.add(Kind.TEAM,new ControlTeam("team-b","Reviewers",true,3L,List.of("alice","checker","bob"),List.of("reviewer-role")));rig.saveGraph();
        assertEquals(403,assertThrows(Failure.class,()->rig.operations.reconcile(checker(),proposal.id(),decision(proposal,true))).status());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptReview(f.invocation().id(),f.invocation().reservedNonce())));
    }
    @Test void originalOwnerAndOwnerSuperAdminCannotUseThePublicAttestationPath(){
        var f=fixture(true,false,"admin");
        rig.graph.add(Kind.AGENT,new ControlAgent("agent","Transferred",null,true,2L,"admin"));
        rig.graph.add(Kind.ROLE,new ControlRole("super-role","Super Admin",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of()));
        rig.graph.add(Kind.TEAM,new ControlTeam("super-team","Owners",true,1L,List.of("alice"),List.of("super-role")));rig.saveGraph();
        var oldOwner=new DirectoryService.Actor(rig.gateway.tenant(),"b".repeat(64),true,false,"bob");
        var superOwner=new DirectoryService.Actor(rig.gateway.tenant(),"a".repeat(64),true,true,"alice");
        for(var actor:List.of(oldOwner,superOwner))
            assertEquals(403,assertThrows(Failure.class,()->rig.operations.proposeReconciliation(actor,f.invocation().id(),
                    rig.graph.codec.json(f.proposal().request()),"owner-review")).status());
    }
    @Test void approvedStateMismatchRollsBackTheDecisionProofAndDerivedOutcome(){
        var f=fixture(true,false,"admin");
        var original=f.proposal().request();
        var wrong=new EnterpriseReconciliationRequest(original.expectedRevision(),EnterpriseReconciledState.FAILED,List.of(),
                original.evidenceDigest(),null,original.attemptSettlement());
        var proposal=rig.graph.codec.model(rig.operations.proposeReconciliation(rig.reviewer,f.invocation().id(),rig.graph.codec.json(wrong),"wrong-final-state").body(),EnterpriseReconciliation.class);
        var before=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome(f.invocation().id()));
        String audit=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,100));
        assertEquals(409,assertThrows(Failure.class,()->rig.operations.reconcile(checker(),proposal.id(),decision(proposal,true))).status());
        assertEquals(EnterpriseReconciliationState.PENDING,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().reconciliation(proposal.id())).state());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptReview(f.invocation().id(),f.invocation().reservedNonce())));
        assertEquals(before,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome(f.invocation().id())));
        assertEquals(audit,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,100)));
    }
    @Test void publicAttestationRequiresPositiveScopeAndKnownConsumedAttemptIds(){
        var f=fixture(true,false,"admin");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)rig.graph.codec.value(rig.graph.codec.json(f.proposal().request()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("attemptSettlement")).put("noFurtherEffects",false);
        assertEquals(400,assertThrows(Failure.class,()->rig.operations.proposeReconciliation(rig.reviewer,f.invocation().id(),body.toString(),"false-scope")).status());
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("attemptSettlement")).put("noFurtherEffects",true).putArray("attemptIds").add("unknown-attempt");
        assertEquals(409,assertThrows(Failure.class,()->rig.operations.proposeReconciliation(rig.reviewer,f.invocation().id(),body.toString(),"foreign-scope")).status());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptReview(f.invocation().id(),f.invocation().reservedNonce())));
    }
    @Test void reviewProofGuardsAlsoHoldUnderPostgresRuntimeRole()throws Exception{
        Assumptions.assumeTrue(System.getenv("CONTROL_TEST_URL")!=null&&System.getenv("CONTROL_TEST_PASSWORD")!=null,"Requires isolated PostgreSQL fixture");
        postgres=true;
        var cases=List.<Runnable>of(this::pendingProposalCannotSettleAndApprovedProofDoesNotRewriteExecutorState,
                this::reviewedUnknownAttemptSettlesWithoutTurningItsReportIntoSuccessOrFailure,
                this::approvedHumanScopeCannotResolveASentAmbiguousGeneration,this::newEvidenceInvalidatesTheReviewedOutcomeRevision,
                this::originalAgentOwnerCannotAcquireReviewIndependenceAfterTransfer,
                this::publicMakerCheckerSettlesOnceWithoutRewritingOrReissuing,
                this::publicApprovalRejectsNewEvidenceButStaleDenialDoesNotSettleAnything,
                this::bothMakersAndCheckersNeedCurrentReviewPermission,
                this::originalOwnerAndOwnerSuperAdminCannotUseThePublicAttestationPath,
                this::approvedStateMismatchRollsBackTheDecisionProofAndDerivedOutcome,
                this::publicAttestationRequiresPositiveScopeAndKnownConsumedAttemptIds);
        for(var scenario:cases){setup();scenario.run();}
        setup();capturedReviewAndItsApprovedSourceCannotBeRewrittenOrDeleted();
    }
}
