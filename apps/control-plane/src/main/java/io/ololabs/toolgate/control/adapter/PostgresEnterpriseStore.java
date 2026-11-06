// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import java.sql.*;
import java.util.*;

/** SQL compare-and-swap and tenant serialization protect replica/restart execution boundaries. */
final class PostgresEnterpriseStore implements EnterpriseStore {
    public Operation operation(String id){return new JdbcOperationLedger(connection,tenant).operation(id);}
    public List<Attempt> attempts(String id,long now){return new JdbcOperationLedger(connection,tenant).attempts(id,now);}
    public List<String> manifestEffects(String operation,String attempt){
        try(var s=query("SELECT effect_id FROM control_attempt_effects WHERE tenant_id=? AND operation_id=? AND attempt_id=? ORDER BY effect_id LIMIT 4097",operation,attempt);var rows=s.executeQuery()){
            var ids=new ArrayList<String>();while(rows.next())ids.add(rows.getString(1));
            if(ids.size()>4096)throw Failure.unavailable();return List.copyOf(ids);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public ManifestSeal manifestSeal(String operation,String attempt){
        try(var s=query("SELECT invocation_revision,created_at,effect_count,manifest_digest,document FROM control_attempt_manifest_seals WHERE tenant_id=? AND operation_id=? AND attempt_id=?",operation,attempt);var rows=s.executeQuery()){
            if(!rows.next())return null;String document=rows.getString(5),digest=rows.getString(4);
            if(!DirectoryService.digest(document).equals(digest))throw Failure.unavailable();
            return new ManifestSeal(operation,attempt,rows.getLong(1),rows.getLong(2),rows.getInt(3),digest,document);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public ManifestSeal sealManifest(String operation,String attempt,long now){
        var old=manifestSeal(operation,attempt);if(old!=null)return old;
        var i=invocation(operation);if(i==null)throw Failure.conflict();
        var lease=executionLease(operation);
        if(lease!=null&&lease.fencingToken().equals(attempt)&&lease.expiresAt()>now)
            saveExecutionLease(new ExecutionLease(operation,attempt,lease.deviceId(),now));
        var ids=manifestEffects(operation,attempt);
        var contents=new LinkedHashMap<String,Object>();contents.put("format","toolgate-attempt-manifest-v1");
        contents.put("operationId",operation);contents.put("attemptId",attempt);contents.put("effects",ids);
        String document=codec.json(contents),digest=DirectoryService.digest(document);
        try(var s=query("INSERT INTO control_attempt_manifest_seals(tenant_id,operation_id,attempt_id,invocation_revision,created_at,effect_count,manifest_digest,document) VALUES(?,?,?,?,?,?,?,?)",
                operation,attempt,i.revision(),now,ids.size(),digest,document)){
            if(s.executeUpdate()!=1)throw Failure.conflict();return manifestSeal(operation,attempt);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public AttemptSettlement attemptSettlement(String operation,String attempt){
        try(var s=query("SELECT proof,invocation_revision,recorded_at,source_digest,document FROM control_attempt_settlements WHERE tenant_id=? AND operation_id=? AND attempt_id=?",operation,attempt);var rows=s.executeQuery()){
            if(!rows.next())return null;String document=rows.getString(5),digest=rows.getString(4);
            if(!DirectoryService.digest(document).equals(digest))throw Failure.unavailable();
            return new AttemptSettlement(operation,attempt,SettlementProof.valueOf(rows.getString(1)),
                    rows.getLong(2),rows.getLong(3),digest,document);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public AttemptSettlement settleAttempt(String operation,String attempt,SettlementProof proof,long now){
        var old=attemptSettlement(operation,attempt);if(old!=null)return old;
        var i=invocation(operation);var seal=manifestSeal(operation,attempt);
        if(i==null||seal==null||proof==null)throw Failure.conflict();
        var contents=new LinkedHashMap<String,Object>();contents.put("format","toolgate-attempt-settlement-v1");
        contents.put("operationId",operation);contents.put("attemptId",attempt);contents.put("proof",proof.name());
        contents.put("manifestDigest",seal.digest());
        String document=codec.json(contents),digest=DirectoryService.digest(document);
        try(var s=query("INSERT INTO control_attempt_settlements(tenant_id,operation_id,attempt_id,proof,invocation_revision,recorded_at,source_digest,document) VALUES(?,?,?,?,?,?,?,?)",
                operation,attempt,proof.name(),i.revision(),now,digest,document)){
            if(s.executeUpdate()!=1)throw Failure.conflict();return attemptSettlement(operation,attempt);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public AttemptReview attemptReview(String operation,String attempt){
        try(var s=query("SELECT reconciliation_id,expected_outcome_revision,recorded_at,source_digest,document FROM control_attempt_reviews WHERE tenant_id=? AND operation_id=? AND attempt_id=?",operation,attempt);var rows=s.executeQuery()){
            if(!rows.next())return null;String document=rows.getString(5),digest=rows.getString(4);
            if(!DirectoryService.digest(document).equals(digest))throw Failure.unavailable();
            return new AttemptReview(operation,attempt,rows.getString(1),rows.getLong(2),rows.getLong(3),digest,document);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public boolean hasAttemptReviews(String operation){
        try(var s=query("SELECT 1 FROM control_attempt_reviews WHERE tenant_id=? AND operation_id=? LIMIT 1",operation);var rows=s.executeQuery()){return rows.next();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public AttemptReview recordAttemptReview(String operation,String attempt,String reconciliation,long now){
        var old=attemptReview(operation,attempt);
        if(old!=null){if(!old.reconciliationId().equals(reconciliation))throw Failure.conflict();return old;}
        var review=reconciliation(reconciliation);var outcome=latestOperationOutcome(operation);var seal=manifestSeal(operation,attempt);
        if(review==null||outcome==null||seal==null||review.request().attemptSettlement()==null)throw Failure.conflict();
        long directoryRevision;
        try(var s=query("SELECT revision FROM control_tenant_authority WHERE tenant_id=?");var rows=s.executeQuery()){
            if(!rows.next())throw Failure.unavailable();directoryRevision=rows.getLong(1);
        }catch(SQLException failure){throw Failure.unavailable();}
        var contents=new LinkedHashMap<String,Object>();contents.put("format","toolgate-attempt-review-v1");
        contents.put("operationId",operation);contents.put("attemptId",attempt);contents.put("manifestDigest",seal.digest());
        contents.put("outcomeSourceDigest",outcome.sourceDigest());contents.put("review",review);
        contents.put("directoryRevision",directoryRevision);contents.put("authorizationEpoch",authorizationEpoch());
        String document=codec.json(contents),digest=DirectoryService.digest(document);
        try(var s=query("INSERT INTO control_attempt_reviews(tenant_id,operation_id,attempt_id,reconciliation_id,expected_outcome_revision,recorded_at,source_digest,document) VALUES(?,?,?,?,?,?,?,?)",
                operation,attempt,reconciliation,review.request().attemptSettlement().expectedOutcomeRevision(),now,digest,document)){
            if(s.executeUpdate()!=1)throw Failure.conflict();return attemptReview(operation,attempt);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public FinalizationPreparation prepareFinalization(String operation,long now){
        return new JdbcOperationFinalization(connection,tenant,this,codec).prepare(operation,now);
    }
    public OperationFinalizationPolicy.Facts finalizationFacts(String operation,long now){
        return new JdbcOperationFinalization(connection,tenant,this,codec).facts(operation,now);
    }
    public List<EnterpriseEffectHistory> effectHistory(String operation,int offset,int limit){
        if(offset<0||offset>4096||limit<1||limit>101)throw Failure.validation();
        var result=new ArrayList<EnterpriseEffectHistory>();
        try(var s=query("SELECT e.effect_id,g.generation,g.attempt_id,e.route_id,e.request_digest,g.state,g.consumer,g.claim_token,"
                +"(SELECT count(*) FROM control_effect_evidence w WHERE w.tenant_id=g.tenant_id AND w.effect_id=g.effect_id AND w.generation=g.generation),"
                +"CASE WHEN EXISTS(SELECT 1 FROM control_effect_evidence w WHERE w.tenant_id=g.tenant_id AND w.effect_id=g.effect_id AND w.generation=g.generation AND w.source='TARGET:'||g.consumer AND w.outcome=g.state) THEN 1 ELSE 0 END "
                +"FROM control_effects e JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id "
                +"WHERE e.tenant_id=? AND e.operation_id=? ORDER BY e.effect_id,g.generation LIMIT ? OFFSET ?",operation,limit,offset);var rows=s.executeQuery()){
            while(rows.next()){
                String state=rows.getString(6),consumer=rows.getString(7),claim=rows.getString(8);
                boolean witnessed=consumer!=null&&claim!=null&&rows.getInt(10)==1;
                String receipt=witnessed&&state.equals("COMPLETED")
                        ?JdbcOperationFinalization.receipt(tenant,rows.getString(1),consumer,claim):null;
                var grade=witnessed?EnterpriseOutcomeGrade.TARGET_CONFIRMED:state.equals("RELEASED")
                        ?EnterpriseOutcomeGrade.CONTROL_PROVEN:EnterpriseOutcomeGrade.REPORTED;
                result.add(new EnterpriseEffectHistory(rows.getString(1),rows.getLong(2),rows.getString(3),
                        rows.getString(4),rows.getString(5),EnterpriseEffectGenerationState.valueOf(state),
                        grade,rows.getLong(9),receipt));
            }
            return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public Set<String> reviewOwners(String operation){
        try(var s=query("SELECT document,source_digest FROM control_operation_review_owners WHERE tenant_id=? AND operation_id=?",operation);var rows=s.executeQuery()){
            if(!rows.next())return null;String document=rows.getString(1);
            if(!DirectoryService.digest(document).equals(rows.getString(2)))throw Failure.unavailable();
            var value=(com.fasterxml.jackson.databind.JsonNode)codec.value(document);var owners=new TreeSet<String>();
            if(!value.path("owners").isArray())throw Failure.unavailable();
            for(var owner:value.path("owners")){
                if(!owner.isTextual())throw Failure.unavailable();
                owners.add(io.ololabs.toolgate.control.domain.Ids.valid(owner.asText()));
            }
            if(owners.isEmpty()||owners.size()>66)throw Failure.unavailable();return Set.copyOf(owners);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void saveReviewOwners(String operation,long directoryRevision,Set<String> owners){
        if(owners==null||owners.isEmpty()||owners.size()>66)throw Failure.validation();
        owners.forEach(io.ololabs.toolgate.control.domain.Ids::valid);
        var contents=new LinkedHashMap<String,Object>();contents.put("format","toolgate-review-owners-v1");
        contents.put("operationId",operation);contents.put("owners",new TreeSet<>(owners));
        String document=codec.json(contents),digest=DirectoryService.digest(document);
        try(var s=query("INSERT INTO control_operation_review_owners(tenant_id,operation_id,directory_revision,source_digest,document) VALUES(?,?,?,?,?)",
                operation,directoryRevision,digest,document)){if(s.executeUpdate()!=1)throw Failure.conflict();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public OutcomeSnapshot latestOperationOutcome(String operation){
        try(var s=query("SELECT document,source_digest,invocation_revision FROM control_operation_outcomes WHERE tenant_id=? AND operation_id=? ORDER BY revision DESC LIMIT 1",operation);var rows=s.executeQuery()){
            return rows.next()?new OutcomeSnapshot(codec.model(rows.getString(1),EnterpriseOperationOutcome.class),rows.getString(2),rows.getLong(3)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public OutcomeSnapshot recordOperationOutcome(EnterpriseOperationOutcome value,String sourceDigest,long invocationRevision){
        var old=latestOperationOutcome(value.operationId());
        if(old!=null&&old.sourceDigest().equals(sourceDigest)&&old.value().state()==value.state()){
            syncBusinessOutcome(old.value());return old;
        }
        String document=checked(value,EnterpriseOperationOutcome.class);
        try(var s=query("INSERT INTO control_operation_outcomes(tenant_id,operation_id,revision,invocation_revision,state,source_digest,recorded_at,document) VALUES(?,?,?,?,?,?,?,?)",
                value.operationId(),value.revision(),invocationRevision,value.state().name(),sourceDigest,value.updatedAtUnixMs(),document)){
            if(s.executeUpdate()!=1)throw Failure.conflict();syncBusinessOutcome(value);return latestOperationOutcome(value.operationId());
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private void syncBusinessOutcome(EnterpriseOperationOutcome value){
        String outcome=switch(value.state()){
            case RUNNING->null;case SUCCEEDED->"COMPLETED";case PARTIAL->"PARTIAL";case FAILED->"FAILED";default->"OUTCOME_UNKNOWN";
        };
        try(var s=bound("UPDATE control_business_keys SET outcome=? WHERE tenant_id=? AND invocation_id=? "
                +"AND NOT(COALESCE(outcome,'') IN('CANCELLED','EXPIRED','REJECTED') AND ?='FAILED') "
                +"AND (outcome IS NULL OR outcome<>? OR CAST(? AS TEXT) IS NULL)",outcome,tenant,value.operationId(),outcome,outcome,outcome)){
            s.executeUpdate();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public String operationEvidenceHead(String operation){
        var head=new LinkedHashMap<String,Object>();
        var queries=new LinkedHashMap<String,String>();
        queries.put("attempts","SELECT count(*) FROM control_operation_attempts WHERE tenant_id=? AND operation_id=?");
        queries.put("snapshots","SELECT count(*) FROM control_attempt_snapshots WHERE tenant_id=? AND operation_id=?");
        queries.put("executionEvidence","SELECT count(*) FROM control_execution_evidence WHERE tenant_id=? AND invocation_id=?");
        queries.put("effects","SELECT count(*) FROM control_effects WHERE tenant_id=? AND operation_id=?");
        queries.put("generationRevision","SELECT COALESCE(sum(revision),0) FROM control_effect_generations WHERE tenant_id=? AND operation_id=?");
        queries.put("effectEvidence","SELECT count(*) FROM control_effect_evidence e WHERE e.tenant_id=? AND EXISTS(SELECT 1 FROM control_effects f WHERE f.tenant_id=e.tenant_id AND f.effect_id=e.effect_id AND f.operation_id=?)");
        queries.put("manifestMembers","SELECT count(*) FROM control_attempt_effects WHERE tenant_id=? AND operation_id=?");
        queries.put("manifestSeals","SELECT count(*) FROM control_attempt_manifest_seals WHERE tenant_id=? AND operation_id=?");
        queries.put("settlements","SELECT count(*) FROM control_attempt_settlements WHERE tenant_id=? AND operation_id=?");
        queries.put("reviews","SELECT count(*) FROM control_attempt_reviews WHERE tenant_id=? AND operation_id=?");
        try{
            for(var item:queries.entrySet())try(var s=query(item.getValue(),operation);var rows=s.executeQuery()){
                if(!rows.next())throw Failure.unavailable();head.put(item.getKey(),rows.getLong(1));
            }
        }catch(SQLException failure){throw Failure.unavailable();}
        return codec.json(head);
    }
    public RecoveryAdmissionPolicy.Scope recoveryScope(String attempt){
        try(var s=query("SELECT device_id,pool_id,image_digest,runtime_digest,descriptor_digest,profile_digest,profile_revision FROM control_attempt_recovery_scopes WHERE tenant_id=? AND attempt_id=?",attempt);var rows=s.executeQuery()){
            return rows.next()?new RecoveryAdmissionPolicy.Scope(rows.getString(1),rows.getString(2),rows.getString(3),
                    rows.getString(4),rows.getString(5),rows.getString(6),rows.getLong(7)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void saveRecoveryScope(String attempt,RecoveryAdmissionPolicy.Scope scope){
        var old=recoveryScope(attempt);
        if(old!=null){if(!old.equals(scope))throw Failure.conflict();return;}
        for(var digest:List.of(scope.imageDigest(),scope.runtimeDigest(),scope.descriptorDigest(),scope.profileDigest()))
            if(!digest.matches("sha256:[a-f0-9]{64}"))throw Failure.validation();
        io.ololabs.toolgate.control.domain.Ids.valid(scope.deviceId());
        io.ololabs.toolgate.control.domain.Ids.valid(scope.poolId());
        try(var s=bound("INSERT INTO control_attempt_recovery_scopes(tenant_id,operation_id,attempt_id,device_id,pool_id,image_digest,runtime_digest,descriptor_digest,profile_digest,profile_revision) SELECT tenant_id,invocation_id,nonce,?,?,?,?,?,?,? FROM control_enterprise_nonces WHERE tenant_id=? AND nonce=?",
                scope.deviceId(),scope.poolId(),scope.imageDigest(),scope.runtimeDigest(),scope.descriptorDigest(),
                scope.profileDigest(),scope.profileRevision(),tenant,attempt)){
            if(s.executeUpdate()!=1)throw Failure.conflict();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<Recovery> recoveries(String operation,int limit){
        if(limit<1||limit>100)throw Failure.validation();
        try(var s=query("SELECT from_revision,to_revision,proof,created_at,document FROM control_operation_recoveries WHERE tenant_id=? AND operation_id=? ORDER BY from_revision LIMIT ?",operation,limit);var rows=s.executeQuery()){
            var history=new ArrayList<Recovery>();
            while(rows.next())history.add(new Recovery(operation,rows.getLong(1),rows.getLong(2),rows.getString(3),rows.getLong(4),rows.getString(5)));
            return List.copyOf(history);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public boolean hasRecovery(String operation){
        try(var s=query("SELECT 1 FROM control_operation_recoveries WHERE tenant_id=? AND operation_id=? LIMIT 1",operation);var rows=s.executeQuery()){
            return rows.next();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public boolean recoveryCoversAttempt(String operation,String attempt){
        try {
            String members=SqliteState.local(connection)?"json_each(r.attempt_ids) j":"jsonb_array_elements_text(r.attempt_ids::jsonb) j(value)";
            try(var s=query("SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=? AND r.operation_id=? AND r.proof='QUALIFIED_BROKER_NO_WRITE' AND EXISTS(SELECT 1 FROM "+members+" WHERE j.value=?) LIMIT 1",operation,attempt);var rows=s.executeQuery()){
                return rows.next();
            }
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public EnterpriseInvocation rebind(EnterpriseInvocation previous,OperationRebindingPolicy.Proof proof,long now){
        return rebind(previous,proof,null,now);
    }
    public EnterpriseInvocation rebindTargetDeduplication(EnterpriseInvocation previous,List<Map<String,Object>> plan,long now){
        if(plan==null||plan.isEmpty()||plan.size()>256)throw Failure.conflict();
        return rebind(previous,OperationRebindingPolicy.Proof.TARGET_DEDUPLICATION,plan,now);
    }
    private EnterpriseInvocation rebind(EnterpriseInvocation previous,OperationRebindingPolicy.Proof proof,List<Map<String,Object>> plan,long now){
        var recovery=recordRecovery(previous,proof,plan,now);
        var snapshot=(com.fasterxml.jackson.databind.JsonNode)codec.value(recovery.document());
        var next=new EnterpriseInvocation(previous.id(),previous.requestDigest(),previous.evaluation(),
                EnterpriseInvocationState.QUEUED,recovery.toRevision(),previous.authorizationEpoch(),null,
                previous.expiresAtUnixMs(),List.of(),previous.downstreamIdempotencyKey(),null,
                previous.diagnosticId(),previous.reconciliation(), null);
        String dispatch=snapshot.path("dispatch").asText(null);
        try {
            // Remove only the active pointer after its exact bytes are durably archived above.
            if(dispatch!=null)try(var s=query("DELETE FROM control_tool_host_dispatches WHERE tenant_id=? AND invocation_id=? AND document=?",previous.id(),dispatch)){
                if(s.executeUpdate()!=1)throw Failure.conflict();
            }
            saveInvocation(next,now,previous.revision());
            try(var s=query("UPDATE control_business_keys SET outcome=NULL WHERE tenant_id=? AND invocation_id=? AND outcome IS NOT NULL",previous.id())){
                s.executeUpdate();
            }
        }catch(SQLException failure){throw Failure.unavailable();}
        return next;
    }
    public Recovery recordRecovery(EnterpriseInvocation previous,OperationRebindingPolicy.Proof proof,long now){
        return recordRecovery(previous,proof,null,now);
    }
    private Recovery recordRecovery(EnterpriseInvocation previous,OperationRebindingPolicy.Proof proof,List<Map<String,Object>> plan,long now){
        var stored=invocation(previous.id());
        if(stored==null||!codec.json(stored).equals(codec.json(previous)))throw Failure.conflict();
        var facts=recoveryFacts(previous.id());
        if(proof==null||(proof==OperationRebindingPolicy.Proof.TARGET_DEDUPLICATION
            ?plan==null||!OperationRebindingPolicy.targetCandidate(facts,now):OperationRebindingPolicy.proof(facts,now)!=proof))throw Failure.conflict();
        var operation=operation(previous.id());
        var ids=new ArrayList<String>();
        String dispatch=null;
        try {
            try(var s=query("SELECT nonce FROM control_enterprise_nonces WHERE tenant_id=? AND invocation_id=? AND purpose<>'SERVICE' ORDER BY nonce LIMIT 65",previous.id());var rows=s.executeQuery()){
                while(rows.next())ids.add(rows.getString(1));
            }
            if(ids.size()>64)throw Failure.conflict();
            try(var s=query("SELECT document FROM control_tool_host_dispatches WHERE tenant_id=? AND invocation_id=?",previous.id());var rows=s.executeQuery()){
                if(rows.next())dispatch=rows.getString(1);
            }
            var snapshot=new LinkedHashMap<String,Object>();
            snapshot.put("format","toolgate-operation-recovery-v1");
            if(plan!=null)snapshot.put("targetPlan",plan);
            snapshot.put("invocation",codec.json(previous));snapshot.put("attemptIds",ids);
            var lease=executionLease(previous.id());
            snapshot.put("lease",lease==null?null:codec.json(lease));
            snapshot.put("dispatch",dispatch);snapshot.put("facts",facts);
            String document=codec.json(snapshot);
            if(document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>33554432)throw Failure.validation();
            var recovery=new Recovery(previous.id(),previous.revision(),Math.addExact(previous.revision(),1),proof.name(),now,document);
            try(var s=query("INSERT INTO control_operation_recoveries(tenant_id,operation_id,from_revision,to_revision,operation_revision,previous_nonce,original_deadline,proof,created_at,attempt_ids,document) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    previous.id(),previous.revision(),recovery.toRevision(),operation.revision(),previous.reservedNonce(),
                    previous.expiresAtUnixMs(),proof.name(),now,codec.json(ids),document)){
                if(s.executeUpdate()!=1)throw Failure.conflict();
            }
            return recovery;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public OperationRebindingPolicy.Facts recoveryFacts(String id){
        return new JdbcOperationLedger(connection,tenant).recoveryFacts(invocation(id));
    }
    public BusinessKey businessKey(String tool,String namespace,String identity,String hash){
        try(var s=query("SELECT fingerprint,caller_agent_id,caller_user_id,invocation_id,retain_until,tombstoned_at,source,dedupe_expires_at,outcome FROM control_business_keys WHERE tenant_id=? AND tool_id=? AND namespace=? AND namespace_identity=? AND business_key_hash=?",tool,namespace,identity,hash);var rows=s.executeQuery()){
            return rows.next()?businessKeyRow(rows,tool,namespace,identity,hash):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private static Long nullableLong(ResultSet rows,int column)throws SQLException{
        long value=rows.getLong(column);return rows.wasNull()?null:value;
    }
    private static BusinessKey businessKeyRow(ResultSet rows,String tool,String namespace,String identity,String hash)throws SQLException{
        Long tombstone=nullableLong(rows,6),dedupe=nullableLong(rows,8);
        return new BusinessKey(tool,namespace,identity,hash,rows.getString(1),rows.getString(2),rows.getString(3),
                rows.getString(4),rows.getLong(5),tombstone,rows.getString(7),dedupe,rows.getString(9));
    }
    private PreparedStatement bound(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);for(int n=0;n<values.length;n++)s.setObject(n+1,values[n]);return s;
    }
    public BusinessKey businessKeyForInvocation(String invocation){
        try(var s=query("SELECT fingerprint,caller_agent_id,caller_user_id,invocation_id,retain_until,tombstoned_at,source,dedupe_expires_at,outcome,tool_id,namespace,namespace_identity,business_key_hash FROM control_business_keys WHERE tenant_id=? AND invocation_id=? LIMIT 2",invocation);var rows=s.executeQuery()){
            if(!rows.next())return null;
            var value=businessKeyRow(rows,rows.getString(10),rows.getString(11),rows.getString(12),rows.getString(13));
            if(rows.next())throw Failure.unavailable();
            return value;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<BusinessKey> businessKeyHistory(String tool,String namespace,String identity,String hash,int limit){
        if(limit<1||limit>100)throw Failure.validation();
        try(var s=query("SELECT fingerprint,caller_agent_id,caller_user_id,invocation_id,retain_until,tombstoned_at,'DEDUPE_WINDOW',dedupe_expires_at,outcome FROM control_business_key_history WHERE tenant_id=? AND tool_id=? AND namespace=? AND namespace_identity=? AND business_key_hash=? ORDER BY archived_at,invocation_id LIMIT ?",tool,namespace,identity,hash,limit);var rows=s.executeQuery()){
            var result=new ArrayList<BusinessKey>();while(rows.next())result.add(businessKeyRow(rows,tool,namespace,identity,hash));return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void replaceBusinessKey(BusinessKey previous,BusinessKey next,long now){
        var operation=operation(previous.invocation());
        var projection=latestOperationOutcome(previous.invocation());
        boolean resolved=projection!=null&&Set.of(EnterpriseOperationState.SUCCEEDED,EnterpriseOperationState.FAILED).contains(projection.value().state())
                ||operation!=null&&operation.state().equals("FAILED")&&(projection==null||projection.value().state()!=EnterpriseOperationState.RECONCILIATION_REQUIRED);
        if(!"DEDUPE_WINDOW".equals(previous.source())||!"DEDUPE_WINDOW".equals(next.source())
                ||previous.dedupeExpiresAt()==null||previous.dedupeExpiresAt()>now
                ||next.dedupeExpiresAt()==null||next.dedupeExpiresAt()<=now||next.retainUntil()<next.dedupeExpiresAt()
                ||next.outcome()!=null||next.tombstonedAt()!=null||previous.invocation().equals(next.invocation())
                ||!previous.tool().equals(next.tool())||!previous.namespace().equals(next.namespace())
                ||!previous.identity().equals(next.identity())||!previous.hash().equals(next.hash())
                ||operation==null||operation.legacy()||!resolved
                ||!previous.equals(businessKey(previous.tool(),previous.namespace(),previous.identity(),previous.hash())))throw Failure.conflict();
        try{
            try(var archive=bound("INSERT INTO control_business_key_history(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,invocation_id,fingerprint,caller_agent_id,caller_user_id,created_at,dedupe_expires_at,retain_until,outcome,tombstoned_at,archived_at) SELECT tenant_id,tool_id,namespace,namespace_identity,business_key_hash,invocation_id,fingerprint,caller_agent_id,caller_user_id,created_at,dedupe_expires_at,retain_until,outcome,tombstoned_at,? FROM control_business_keys WHERE tenant_id=? AND tool_id=? AND namespace=? AND namespace_identity=? AND business_key_hash=? AND invocation_id=? AND source='DEDUPE_WINDOW'",now,tenant,previous.tool(),previous.namespace(),previous.identity(),previous.hash(),previous.invocation())){
                if(archive.executeUpdate()!=1)throw Failure.conflict();
            }
            try(var update=bound("UPDATE control_business_keys SET fingerprint=?,caller_agent_id=?,caller_user_id=?,invocation_id=?,created_at=?,dedupe_expires_at=?,retain_until=?,outcome=NULL,tombstoned_at=NULL WHERE tenant_id=? AND tool_id=? AND namespace=? AND namespace_identity=? AND business_key_hash=? AND invocation_id=? AND dedupe_expires_at<=?",
                    next.fingerprint(),next.agent(),next.user(),next.invocation(),now,next.dedupeExpiresAt(),next.retainUntil(),tenant,
                    previous.tool(),previous.namespace(),previous.identity(),previous.hash(),previous.invocation(),now)){
                if(update.executeUpdate()!=1)throw Failure.conflict();
            }
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public int tombstoneExpiredBusinessKeys(long now,int limit){
        if(limit<1||limit>100)throw Failure.validation();
        var keys=new ArrayList<String[]>();
        try{
            try(var s=query("SELECT k.tool_id,k.namespace,k.namespace_identity,k.business_key_hash,k.invocation_id FROM control_business_keys k JOIN control_operations o ON o.tenant_id=k.tenant_id AND o.operation_id=k.invocation_id WHERE k.tenant_id=? AND k.retain_until<=? AND k.tombstoned_at IS NULL AND k.outcome IS NOT NULL AND k.outcome<>'OUTCOME_UNKNOWN' AND NOT o.legacy AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v  WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')  AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))  AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id  AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id))) ORDER BY k.retain_until,k.tool_id,k.namespace,k.namespace_identity,k.business_key_hash LIMIT ?",now,limit);var rows=s.executeQuery()){
                while(rows.next())keys.add(new String[]{rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5)});
            }
            int count=0;
            for(var key:keys)try(var s=bound("UPDATE control_business_keys SET tombstoned_at=? WHERE tenant_id=? AND tool_id=? AND namespace=? AND namespace_identity=? AND business_key_hash=? AND invocation_id=? AND tombstoned_at IS NULL AND retain_until<=?",now,tenant,key[0],key[1],key[2],key[3],key[4],now)){
                count+=s.executeUpdate();
            }
            return count;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public boolean businessInvocation(String id){try(var s=query("SELECT 1 FROM control_business_keys WHERE tenant_id=? AND invocation_id=?",id);var rows=s.executeQuery()){return rows.next();}catch(SQLException failure){throw Failure.unavailable();}}
    public void saveBusinessKey(BusinessKey key,long now){
        try(var s=query("INSERT INTO control_business_keys(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,source,fingerprint,caller_agent_id,caller_user_id,invocation_id,created_at,retain_until,dedupe_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",key.tool(),key.namespace(),key.identity(),key.hash(),key.source(),key.fingerprint(),key.agent(),key.user(),key.invocation(),now,key.retainUntil(),key.dedupeExpiresAt())){if(s.executeUpdate()!=1)throw Failure.conflict();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public void saveExecutionEvidence(String id,String fence,String digest,String document,long now){
        try{
            boolean local=SqliteState.local(connection),qualified;
            String state=local?"json_extract(ep.document,'$.state')":"ep.document::jsonb->>'state'";
            String enabled=local?"json_extract(d.document,'$.enabled')=1":"d.document::jsonb->>'enabled'='true'";
            String device=local?"json_extract(i.document,'$.evaluation.context.deviceId')":"i.document::jsonb->'evaluation'->'context'->>'deviceId'";
            try(var check=query("SELECT 1 FROM control_enterprise_nonces n JOIN control_attempt_recovery_scopes s ON s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce "
                    +"JOIN control_enterprise_invocations i ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id "
                    +"JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id "
                    +"JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id "
                    +"WHERE n.tenant_id=? AND n.invocation_id=? AND n.nonce=? AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=? "
                    +"AND s.device_id="+device+" AND "+state+"='ACTIVE' AND "+enabled+(local?"":" FOR SHARE OF ep,d"),id,fence,now);
                    var rows=check.executeQuery()){qualified=rows.next();}
            try(var insert=query("INSERT INTO control_execution_evidence(tenant_id,invocation_id,fencing_token,report_digest,document,recorded_at,qualified_report) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    id,fence,digest,document,now,qualified)){insert.executeUpdate();}
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public ExecutionLease executionLease(String id){
        try(var s=query("SELECT fencing_token,device_id,expires_at FROM control_execution_leases WHERE tenant_id=? AND invocation_id=?",id);var r=s.executeQuery()){
            return r.next()?new ExecutionLease(id,r.getString(1),r.getString(2),r.getLong(3)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void saveExecutionLease(ExecutionLease lease){
        try(var s=query("INSERT INTO control_execution_leases(tenant_id,invocation_id,fencing_token,device_id,expires_at) VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,invocation_id) DO UPDATE SET fencing_token=excluded.fencing_token,device_id=excluded.device_id,expires_at=excluded.expires_at",lease.invocationId(),lease.fencingToken(),lease.deviceId(),lease.expiresAt())){
            if(s.executeUpdate()!=1)throw Failure.conflict();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private final Connection connection;private final String tenant;private final ContractCodec codec;
    private final io.ololabs.toolgate.contracts.v2.InsightsMode insights;
    PostgresEnterpriseStore(Connection connection,String tenant,ContractCodec codec){this(connection,tenant,codec,io.ololabs.toolgate.contracts.v2.InsightsMode.NONE);}
    PostgresEnterpriseStore(Connection connection,String tenant,ContractCodec codec,io.ololabs.toolgate.contracts.v2.InsightsMode insights){this.connection=connection;this.tenant=tenant;this.codec=codec;this.insights=insights;}
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);s.setString(1,tenant);for(int i=0;i<values.length;i++)s.setObject(i+2,values[i]);return s;
    }
    public Long userCredentialDeadline(String id){try(var s=query("SELECT expires_at FROM control_invocation_authority WHERE tenant_id=? AND invocation_id=?",id);var rows=s.executeQuery()){return rows.next()?rows.getLong(1):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public void userCredentialDeadline(String id,long expiry){try(var s=query(SqliteState.sql(connection,"INSERT INTO control_invocation_authority(tenant_id,invocation_id,expires_at) VALUES(?,?,?) ON CONFLICT(tenant_id,invocation_id) DO UPDATE SET expires_at=GREATEST(control_invocation_authority.expires_at,excluded.expires_at)"),id,expiry)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public boolean operationCancelled(String id){try(var s=query("SELECT 1 FROM control_operation_cancellations WHERE tenant_id=? AND invocation_id=?",id);var rows=s.executeQuery()){return rows.next();}catch(SQLException failure){throw Failure.unavailable();}}
    public void cancelOperation(String id){try(var s=query("INSERT INTO control_operation_cancellations(tenant_id,invocation_id) VALUES(?,?) ON CONFLICT DO NOTHING",id)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public long authorizationEpoch(){try(var s=query("SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=?");var rows=s.executeQuery()){if(!rows.next())throw Failure.unavailable();return rows.getLong(1);}catch(SQLException failure){throw Failure.unavailable();}}
    public List<EnterpriseInvocation> invocations(String after,int limit){try(var s=query("SELECT document FROM control_enterprise_invocations WHERE tenant_id=? AND invocation_id>? ORDER BY invocation_id LIMIT ?",after,limit);var rows=s.executeQuery()){var values=new ArrayList<EnterpriseInvocation>();while(rows.next())values.add(codec.model(rows.getString(1),EnterpriseInvocation.class));return List.copyOf(values);}catch(SQLException failure){throw Failure.unavailable();}}
    public List<EnterpriseInvocation> expiredInvocations(long now,int limit){try(var s=query("SELECT i.document FROM control_enterprise_invocations i WHERE i.tenant_id=? AND (i.expires_at<=? OR (i.state='EXECUTING' AND EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id AND l.expires_at<=?))) AND i.state IN('PENDING_APPROVAL','QUEUED','RESERVED','EXECUTING') ORDER BY i.invocation_id LIMIT ?",now,now,limit);var rows=s.executeQuery()){var values=new ArrayList<EnterpriseInvocation>();while(rows.next())values.add(codec.model(rows.getString(1),EnterpriseInvocation.class));return List.copyOf(values);}catch(SQLException failure){throw Failure.unavailable();}}
    public EnterpriseReconciliation reconciliation(String id){try(var s=query("SELECT document FROM control_outcome_reconciliations WHERE tenant_id=? AND reconciliation_id=?",id);var rows=s.executeQuery()){return rows.next()?codec.model(rows.getString(1),EnterpriseReconciliation.class):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public void saveReconciliation(EnterpriseReconciliation value,long expected){try{int count;if(expected==0)try(var s=query("INSERT INTO control_outcome_reconciliations(tenant_id,reconciliation_id,revision,document) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",value.id(),value.revision(),codec.json(value))){count=s.executeUpdate();}else try(var s=connection.prepareStatement("UPDATE control_outcome_reconciliations SET revision=?,document=? WHERE tenant_id=? AND reconciliation_id=? AND revision=?")){s.setLong(1,value.revision());s.setString(2,codec.json(value));s.setString(3,tenant);s.setString(4,value.id());s.setLong(5,expected);count=s.executeUpdate();}if(count!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
    public EnterpriseOperationalStatus operationalStatus(long now){try(var s=query("SELECT count(*),COALESCE(min(occurred_at),0) FROM control_authorization_outbox WHERE tenant_id=? AND published_at IS NULL");var events=s.executeQuery()){events.next();long pending=events.getLong(1),oldest=events.getLong(2);try(var count=query("SELECT count(*) FROM control_enterprise_invocations WHERE tenant_id=? AND state='OUTCOME_UNKNOWN'");var unknown=count.executeQuery()){unknown.next();try(var expired=query("SELECT count(*) FROM control_enterprise_invocations WHERE tenant_id=? AND state='EXECUTING' AND expires_at<=?",now);var rows=expired.executeQuery()){rows.next();return new EnterpriseOperationalStatus(pending,oldest,unknown.getLong(1),rows.getLong(1),10000L,0L,0L,5000L);}}}catch(SQLException failure){throw Failure.unavailable();}}
    public String creator(String kind,String id){try(var s=query("SELECT creator_user_id FROM control_queued_authority WHERE tenant_id=? AND kind=? AND record_id=?",kind,id);var rows=s.executeQuery()){return rows.next()?rows.getString(1):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public void rememberCreator(String kind,String id,String user){try(var s=query("INSERT INTO control_queued_authority(tenant_id,kind,record_id,creator_user_id) VALUES(?,?,?,?) ON CONFLICT(tenant_id,kind,record_id) DO UPDATE SET creator_user_id=excluded.creator_user_id",kind,id,user)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public long budgetUsage(String budget,long since,String excludingInvocation){try(var s=query("SELECT count(*) FROM control_execution_budgets WHERE tenant_id=? AND budget_id=? AND created_at>=? AND invocation_id<>?",budget,since,excludingInvocation==null?"":excludingInvocation);var rows=s.executeQuery()){rows.next();return rows.getLong(1);}catch(SQLException failure){throw Failure.unavailable();}}
    public void reserveBudget(String budget,String invocation,long now){try(var s=query("INSERT INTO control_execution_budgets(tenant_id,budget_id,invocation_id,created_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",budget,invocation,now)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public void acknowledge(EnterpriseAdoptionStatus a){try(var s=query("INSERT INTO control_enterprise_adoptions(tenant_id,actor_type,actor_id,directory_revision,snapshot_sequence,graph_digest,observed_at,authorization_epoch) VALUES(?,'DEVICE',?,?,0,?,?,?) ON CONFLICT(tenant_id,actor_type,actor_id) DO UPDATE SET directory_revision=excluded.directory_revision,authorization_epoch=excluded.authorization_epoch,graph_digest=excluded.graph_digest,observed_at=excluded.observed_at WHERE excluded.directory_revision>=control_enterprise_adoptions.directory_revision AND excluded.authorization_epoch>=control_enterprise_adoptions.authorization_epoch",a.deviceId(),a.directoryRevision(),a.graphDigest(),a.observedAtUnixMs(),a.authorizationEpoch())){if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
    public List<EnterpriseAdoptionStatus> adoptions(){try(var s=query("SELECT actor_id,directory_revision,authorization_epoch,graph_digest,observed_at FROM control_enterprise_adoptions WHERE tenant_id=? AND actor_type='DEVICE' ORDER BY actor_id LIMIT 512");var rows=s.executeQuery()){var result=new ArrayList<EnterpriseAdoptionStatus>();while(rows.next())result.add(new EnterpriseAdoptionStatus(rows.getString(1),rows.getLong(2),rows.getLong(3),rows.getString(4),rows.getLong(5)));return List.copyOf(result);}catch(SQLException failure){throw Failure.unavailable();}}
    public void published(long revision,long now){try(var s=connection.prepareStatement("UPDATE control_authorization_outbox SET published_at=? WHERE tenant_id=? AND revision<=? AND published_at IS NULL")){s.setLong(1,now);s.setString(2,tenant);s.setLong(3,revision);s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    private Secret secret(ResultSet rows)throws SQLException{return new Secret(rows.getString(1),rows.getString(2),rows.getString(3),rows.getLong(4),rows.getLong(5),rows.getString(6));}
    public Secret secret(String name){try(var s=query("SELECT name,tool_group_id,device_group_id,version,key_version,cipher FROM control_secrets WHERE tenant_id=? AND name=?",name);var rows=s.executeQuery()){return rows.next()?secret(rows):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public List<Secret> secrets(){try(var s=query("SELECT name,tool_group_id,device_group_id,version,key_version,cipher FROM control_secrets WHERE tenant_id=? ORDER BY name LIMIT 257");var rows=s.executeQuery()){var result=new ArrayList<Secret>();while(rows.next())result.add(secret(rows));return List.copyOf(result);}catch(SQLException failure){throw Failure.unavailable();}}
    public void saveSecret(Secret value){try(var s=query("INSERT INTO control_secrets(tenant_id,name,tool_group_id,device_group_id,version,key_version,cipher) VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,name) DO UPDATE SET tool_group_id=excluded.tool_group_id,device_group_id=excluded.device_group_id,version=excluded.version,key_version=excluded.key_version,cipher=excluded.cipher WHERE control_secrets.version=excluded.version-1",value.name(),value.toolGroupId(),value.deviceGroupId(),value.version(),value.keyVersion(),value.cipher())){if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
    private VaultKey vaultKey(ResultSet r)throws SQLException{return new VaultKey(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5));}
    public VaultKey vaultKey(long version){try(var s=query("SELECT version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key FROM control_tenant_vault_keys WHERE tenant_id=? AND version=?",version);var r=s.executeQuery()){return r.next()?vaultKey(r):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public VaultKey currentVaultKey(){try(var s=query("SELECT version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key FROM control_tenant_vault_keys WHERE tenant_id=? AND state='ACTIVE'");var r=s.executeQuery()){return r.next()?vaultKey(r):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public List<VaultKey> vaultKeys(){try(var s=query("SELECT version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key FROM control_tenant_vault_keys WHERE tenant_id=? ORDER BY version LIMIT 1025");var r=s.executeQuery()){var result=new ArrayList<VaultKey>();while(r.next())result.add(vaultKey(r));if(result.size()>1024)throw Failure.unavailable();return List.copyOf(result);}catch(SQLException failure){throw Failure.unavailable();}}
    public void insertVaultKey(VaultKey key){try(var s=query("INSERT INTO control_tenant_vault_keys(tenant_id,version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key) VALUES(?,?,?,?,?,?)",key.version(),key.state(),key.providerReference(),key.wrappedEncryptionKey(),key.wrappedRetryKey())){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public void updateVaultKey(VaultKey key,String expectedState){try(var s=connection.prepareStatement("UPDATE control_tenant_vault_keys SET state=?,provider_reference=?,wrapped_encryption_key=?,wrapped_retry_key=? WHERE tenant_id=? AND version=? AND state=?")){s.setString(1,key.state());s.setString(2,key.providerReference());s.setString(3,key.wrappedEncryptionKey());s.setString(4,key.wrappedRetryKey());s.setString(5,tenant);s.setLong(6,key.version());s.setString(7,expectedState);if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
    public boolean credentialUsed(String digest){try(var s=query("SELECT 1 FROM control_credential_history WHERE tenant_id=? AND credential_sha256=?",digest);var rows=s.executeQuery()){return rows.next();}catch(SQLException failure){throw Failure.unavailable();}}
    public void rememberCredential(String digest){try(var s=query("INSERT INTO control_credential_history(tenant_id,credential_sha256) VALUES(?,?) ON CONFLICT DO NOTHING",digest)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public boolean recoveryApplied(String digest){try(var s=query("SELECT 1 FROM control_reviewed_recovery WHERE tenant_id=? AND review_digest=?",digest);var rows=s.executeQuery()){return rows.next();}catch(SQLException failure){throw Failure.unavailable();}}
    public void rememberRecovery(String digest){try(var s=query("INSERT INTO control_reviewed_recovery(tenant_id,review_digest) VALUES(?,?)",digest)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public EnterpriseConfigurationChange configuration(String id){try(var s=query("SELECT document FROM control_configuration_changes WHERE tenant_id=? AND change_id=?",id);var rows=s.executeQuery()){return rows.next()?codec.model(rows.getString(1),EnterpriseConfigurationChange.class):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public List<EnterpriseConfigurationChange> configurations(String after,int limit){try(var s=query("SELECT document FROM control_configuration_changes WHERE tenant_id=? AND change_id>? ORDER BY change_id LIMIT ?",after,limit);var rows=s.executeQuery()){var result=new ArrayList<EnterpriseConfigurationChange>();while(rows.next())result.add(codec.model(rows.getString(1),EnterpriseConfigurationChange.class));return List.copyOf(result);}catch(SQLException failure){throw Failure.unavailable();}}
    public void saveConfiguration(EnterpriseConfigurationChange value,long expected){
        var json=codec.json(value);if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2097152)throw Failure.validation();codec.model(json,EnterpriseConfigurationChange.class);
        try{int count;if(expected==0)try(var s=query("INSERT INTO control_configuration_changes(tenant_id,change_id,revision,request_digest,document) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",value.id(),value.revision(),value.requestDigest(),json)){count=s.executeUpdate();}
            else try(var s=connection.prepareStatement("UPDATE control_configuration_changes SET revision=?,document=? WHERE tenant_id=? AND change_id=? AND revision=? AND request_digest=?")){s.setLong(1,value.revision());s.setString(2,json);s.setString(3,tenant);s.setString(4,value.id());s.setLong(5,expected);s.setString(6,value.requestDigest());count=s.executeUpdate();}if(count!=1)throw Failure.conflict();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public EnterpriseInvocation invocation(String id){try(var s=query("SELECT document FROM control_enterprise_invocations WHERE tenant_id=? AND invocation_id=?",id);var rows=s.executeQuery()){return rows.next()?codec.model(rows.getString(1),EnterpriseInvocation.class):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public void saveInvocation(EnterpriseInvocation value,long createdAt,long expectedRevision){
        // Derived response metadata is never persisted as executor or authorization evidence.
        if(value.operationOutcome()!=null)value=new EnterpriseInvocation(value.id(),value.requestDigest(),value.evaluation(),
                value.state(),value.revision(),value.authorizationEpoch(),value.reservedNonce(),value.expiresAtUnixMs(),
                value.completedResources(),value.downstreamIdempotencyKey(),value.resultDigest(),value.diagnosticId(),value.reconciliation(), null);
        String document=checked(value,EnterpriseInvocation.class);int changed;String previous=null;
        try{
            // Every ledger transition is recorded as a platform run event in the same transaction.
            if(expectedRevision!=0)try(var s=query("SELECT state FROM control_enterprise_invocations WHERE tenant_id=? AND invocation_id=? AND revision=?",value.id(),expectedRevision);var rows=s.executeQuery()){if(rows.next())previous=rows.getString(1);}
            if(expectedRevision==0)try(var s=query("INSERT INTO control_enterprise_invocations(tenant_id,invocation_id,request_digest,state,authorization_epoch,created_at,expires_at,revision,document) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",value.id(),value.requestDigest(),value.state().name(),value.authorizationEpoch(),createdAt,value.expiresAtUnixMs(),value.revision(),document)){changed=s.executeUpdate();}
            else try(var s=connection.prepareStatement("UPDATE control_enterprise_invocations SET state=?,authorization_epoch=?,expires_at=?,revision=?,document=? WHERE tenant_id=? AND invocation_id=? AND revision=? AND request_digest=?")){
                s.setString(1,value.state().name());s.setLong(2,value.authorizationEpoch());s.setLong(3,value.expiresAtUnixMs());s.setLong(4,value.revision());s.setString(5,document);s.setString(6,tenant);s.setString(7,value.id());s.setLong(8,expectedRevision);s.setString(9,value.requestDigest());changed=s.executeUpdate();
            }
            if(changed!=1)throw Failure.conflict();
        }catch(SQLException failure){throw Failure.unavailable();}
        new JdbcOperationLedger(connection,tenant).observe(value,document,createdAt,expectedRevision==0);
        boolean terminal=switch(value.state()){case PENDING_APPROVAL,QUEUED,RESERVED,DISPATCHED,EXECUTING->false;default->true;};
        if(terminal&&value.reservedNonce()!=null){
            boolean known;
            try(var s=query("SELECT 1 FROM control_operation_attempts WHERE tenant_id=? AND operation_id=? AND attempt_id=?",value.id(),value.reservedNonce());var rows=s.executeQuery()){known=rows.next();}
            catch(SQLException failure){throw Failure.unavailable();}
            if(known)sealManifest(value.id(),value.reservedNonce(),createdAt);
        }
        // Commit terminal business-key outcome with its invocation, including watchdog settlement.
        String outcome=switch(value.state()){
            case SUCCEEDED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case EXPIRED -> "EXPIRED";
            case OUTCOME_UNKNOWN,PARTIAL -> "OUTCOME_UNKNOWN";
            default -> null;
        };
        if(outcome!=null)try(var s=connection.prepareStatement("UPDATE control_business_keys SET outcome=? WHERE tenant_id=? AND invocation_id=? AND (outcome IS NULL OR (outcome='OUTCOME_UNKNOWN' AND ? IN ('COMPLETED','FAILED')))")){
            s.setString(1,outcome);s.setString(2,tenant);s.setString(3,value.id());s.setString(4,outcome);s.executeUpdate();
        }catch(SQLException failure){throw Failure.unavailable();}
        if(!value.state().name().equals(previous)){
            long now=System.currentTimeMillis();
            RunEvents.recordState(new JdbcRunEventStore(connection,tenant),value.id(),value.state().name(),now);
            if(insights!=io.ololabs.toolgate.contracts.v2.InsightsMode.NONE)captureInsightsFact(value,previous,now);
        }
    }
    /** ADR 031 §4: one immutable fact per state-changing transition, in the ledger's own transaction, so a failure here
     *  fails the transition like a failed audit write. The per-invocation sequence lives on the ledger row. */
    private void captureInsightsFact(EnterpriseInvocation value,String previous,long now){
        long seq;
        try(var s=connection.prepareStatement(SqliteState.sql(connection,"UPDATE control_enterprise_invocations SET insights_fact_seq=insights_fact_seq+1 WHERE tenant_id=? AND invocation_id=? RETURNING insights_fact_seq"))){
            s.setString(1,tenant);s.setString(2,value.id());try(var rows=s.executeQuery()){if(!rows.next())throw Failure.conflict();seq=rows.getLong(1);}
        }catch(SQLException failure){throw Failure.unavailable();}
        var c=value.evaluation().context();var detail=Insights.JSON.createObjectNode();
        if(seq==1){
            detail.put("toolId",value.evaluation().toolId()).put("action",value.evaluation().action());
            if(c.bindingId()!=null)detail.put("clientId",c.bindingId());if(c.userId()!=null)detail.put("userId",c.userId());if(c.agentId()!=null)detail.put("agentId",c.agentId());
        }
        String state=value.state().name(),executionId=null;
        if(state.equals("EXECUTING")){
            if(c.deviceId()!=null)detail.put("executorId",c.deviceId());
            if(value.reservedNonce()!=null)executionId=Insights.executionId(tenant,value.id(),value.reservedNonce());
        }
        // The expiry sweep ends an invocation whose deadline passed; any other failure is the tool's report.
        if((state.equals("FAILED")||state.equals("OUTCOME_UNKNOWN"))&&value.expiresAtUnixMs()<=now)detail.put("cause","LEASE_EXPIRED");
        new JdbcInsightsStore(connection,tenant).insertFact(new Insights.Fact(value.id(),seq,value.revision(),previous,state,now,executionId,detail.toString()));
    }
    public void insightsContext(String invocationId,io.ololabs.toolgate.contracts.v2.ExecutionContext context){
        if(insights==io.ololabs.toolgate.contracts.v2.InsightsMode.NONE||context==null)return;
        var declared=Insights.JSON.createObjectNode();
        if(context.toolCallId()!=null)declared.put("toolCallId",context.toolCallId());
        if(context.sessionId()!=null)declared.put("sessionId",context.sessionId());
        if(context.agentRunId()!=null)declared.put("agentRunId",context.agentRunId());
        // A declared parent is kept only when it names another invocation of this tenant (ADR 031 §12).
        var parent=context.parentInvocationId();
        if(parent!=null&&!parent.equals(invocationId)&&invocation(parent)!=null)declared.put("parentInvocationId",parent);
        if(!declared.isEmpty())new JdbcInsightsStore(connection,tenant).declare(invocationId,declared.toString());
    }
    public long invocationsSince(long since){try(var s=query("SELECT count(*) FROM control_enterprise_invocations WHERE tenant_id=? AND created_at>=? AND state NOT IN('CANCELLED','EXPIRED')",since);var rows=s.executeQuery()){rows.next();return rows.getLong(1);}catch(SQLException failure){throw Failure.unavailable();}}
    public EnterpriseApproval approval(String id){try(var s=query("SELECT document FROM control_enterprise_approvals WHERE tenant_id=? AND approval_id=?",id);var rows=s.executeQuery()){return rows.next()?codec.model(rows.getString(1),EnterpriseApproval.class):null;}catch(SQLException failure){throw Failure.unavailable();}}
    public List<EnterpriseApproval> approvals(String after,int limit){try(var s=query("SELECT document FROM control_enterprise_approvals WHERE tenant_id=? AND approval_id>? ORDER BY approval_id LIMIT ?",after,limit);var rows=s.executeQuery()){var values=new ArrayList<EnterpriseApproval>();while(rows.next())values.add(codec.model(rows.getString(1),EnterpriseApproval.class));return List.copyOf(values);}catch(SQLException failure){throw Failure.unavailable();}}
    public void saveApproval(EnterpriseApproval value,long expectedRevision){
        String document=checked(value,EnterpriseApproval.class);int changed;
        try{
            if(expectedRevision==0)try(var s=query("INSERT INTO control_enterprise_approvals(tenant_id,approval_id,approval_type,invocation_id,request_digest,authorization_epoch,expires_at,revision,document) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",value.id(),value.approvalType().name(),value.invocationId(),value.requestDigest(),value.authorizationEpoch(),value.expiresAtUnixMs(),value.revision(),document)){changed=s.executeUpdate();}
            else try(var s=connection.prepareStatement("UPDATE control_enterprise_approvals SET expires_at=?,revision=?,document=? WHERE tenant_id=? AND approval_id=? AND revision=? AND request_digest=?")){s.setLong(1,value.expiresAtUnixMs());s.setLong(2,value.revision());s.setString(3,document);s.setString(4,tenant);s.setString(5,value.id());s.setLong(6,expectedRevision);s.setString(7,value.requestDigest());changed=s.executeUpdate();}
            if(changed!=1)throw Failure.conflict();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public Nonce nonce(String id){try(var s=query("SELECT invocation_id,evaluation_digest,authorization_epoch,expires_at,consumed_at,issued_at FROM control_enterprise_nonces WHERE tenant_id=? AND nonce=?",id);var rows=s.executeQuery()){if(!rows.next())return null;long consumed=rows.getLong(5);boolean absent=rows.wasNull();return new Nonce(id,rows.getString(1),rows.getString(2),rows.getLong(3),rows.getLong(6),rows.getLong(4),absent?null:consumed);}catch(SQLException failure){throw Failure.unavailable();}}
    public void reserveNonce(Nonce value){reserveNonce(value,false);}
    public void reserveExecutionNonce(Nonce value){reserveNonce(value,true);}
    private void reserveNonce(Nonce value,boolean execution){
        try(var s=query("INSERT INTO control_enterprise_nonces(tenant_id,nonce,invocation_id,evaluation_digest,authorization_epoch,issued_at,expires_at,purpose) VALUES(?,?,?,?,?,?,?,?)",value.id(),value.invocationId(),value.evaluationDigest(),value.authorizationEpoch(),value.issuedAt(),value.expiresAt(),execution?"EXECUTION":"SERVICE")){
            s.executeUpdate();if(execution)new JdbcOperationLedger(connection,tenant).reserve(value);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void consumeNonce(String id,long now){try(var s=connection.prepareStatement("UPDATE control_enterprise_nonces SET consumed_at=? WHERE tenant_id=? AND nonce=? AND consumed_at IS NULL AND expires_at>?")){s.setLong(1,now);s.setString(2,tenant);s.setString(3,id);s.setLong(4,now);if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
    private <T> String checked(T value,Class<T> model){String json=codec.json(value);if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw Failure.validation();codec.model(json,model);return json;}
}
