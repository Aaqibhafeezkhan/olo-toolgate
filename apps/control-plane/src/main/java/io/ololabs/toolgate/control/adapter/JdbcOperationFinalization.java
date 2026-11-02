// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import java.sql.*;
import java.util.*;
import static io.ololabs.toolgate.control.application.OperationFinalizationPolicy.*;

/** Complete bounded journal projection; no executor/caller can supply these proof flags. */
final class JdbcOperationFinalization {
    private static final Set<String> ACTIVE=Set.of("PENDING_APPROVAL","QUEUED","RESERVED","DISPATCHED","EXECUTING");
    private final Connection connection;
    private final String tenant;
    private final EnterpriseStore enterprise;
    private final ContractCodec codec;
    private record Row(String id,String state,Long consumed,long issued,long expires) {}
    private record Write(String effect,String attempt,String state,boolean witnessed,String receipt) {
        boolean resolved(){return state.equals("RELEASED")||Set.of("COMPLETED","FAILED").contains(state)&&witnessed;}
        Generation generation(){return new Generation(state,witnessed,receipt,false,resolved());}
    }
    JdbcOperationFinalization(Connection connection,String tenant,EnterpriseStore enterprise,ContractCodec codec){
        this.connection=connection;this.tenant=tenant;this.enterprise=enterprise;this.codec=codec;
    }
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);s.setString(1,tenant);
        for(int n=0;n<values.length;n++)s.setObject(n+2,values[n]);return s;
    }
    private List<Row> attempts(String operation,long now){
        var values=new ArrayList<Row>();
        try(var s=query("SELECT a.attempt_id,n.consumed_at,a.issued_at,a.expires_at,h.state FROM control_operation_attempts a JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id LEFT JOIN control_attempt_snapshots h ON h.tenant_id=a.tenant_id AND h.attempt_id=a.attempt_id AND h.revision=(SELECT max(x.revision) FROM control_attempt_snapshots x WHERE x.tenant_id=a.tenant_id AND x.attempt_id=a.attempt_id) WHERE a.tenant_id=? AND a.operation_id=? ORDER BY a.issued_at,a.attempt_id LIMIT 65",operation);var rows=s.executeQuery()){
            while(rows.next()){
                long value=rows.getLong(2);Long consumed=rows.wasNull()?null:value;
                long expiry=rows.getLong(4);String state=rows.getString(5);
                if(state==null)state=consumed==null?(expiry<=now?"EXPIRED":"RESERVED"):"OUTCOME_UNKNOWN";
                else if(consumed==null&&expiry<=now&&Set.of("RESERVED","DISPATCHED").contains(state))state="EXPIRED";
                else if(consumed!=null&&Set.of("RESERVED","DISPATCHED","EXPIRED").contains(state))state="OUTCOME_UNKNOWN";
                values.add(new Row(rows.getString(1),state,consumed,rows.getLong(3),expiry));
            }
            return List.copyOf(values);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private List<Write> writes(String operation){
        var values=new ArrayList<Write>();
        try(var s=query("SELECT e.effect_id,g.attempt_id,g.state,g.consumer,g.claim_token,CASE WHEN EXISTS(SELECT 1 FROM control_effect_evidence w WHERE w.tenant_id=g.tenant_id AND w.effect_id=g.effect_id AND w.generation=g.generation AND w.source='TARGET:'||g.consumer AND w.outcome=g.state) THEN 1 ELSE 0 END FROM control_effects e LEFT JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id WHERE e.tenant_id=? AND e.operation_id=? ORDER BY e.effect_id,g.generation LIMIT 4097",operation);var rows=s.executeQuery()){
            while(rows.next()){
                String state=rows.getString(3),consumer=rows.getString(4),claim=rows.getString(5);
                boolean witnessed=consumer!=null&&claim!=null&&rows.getInt(6)==1;
                // This is the authenticated, shared target-claim identity, not the response-body digest.
                String receipt=witnessed&&"COMPLETED".equals(state)
                        ?receipt(tenant,rows.getString(1),consumer,claim):null;
                values.add(new Write(rows.getString(1),rows.getString(2),state==null?"MISSING":state,witnessed,receipt));
            }
            return List.copyOf(values);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    static String receipt(String tenant,String effect,String consumer,String claim){
        return DirectoryService.digest("toolgate-target-claim-receipt-v1\n"+tenant+"\n"+effect+"\n"+consumer+"\n"+claim);
    }
    private record LateReports(Set<String> successful,boolean complete) {}
    private LateReports lateReports(String operation){
        var successful=new HashSet<String>();int count=0;long bytes=0;
        try(var s=query("SELECT fencing_token,document FROM control_execution_evidence WHERE tenant_id=? AND invocation_id=? AND qualified_report=true ORDER BY recorded_at,report_digest LIMIT 4097",operation);
                var rows=s.executeQuery()){
            while(rows.next()){
                if(++count>4096)return new LateReports(successful,false);
                String document=rows.getString(2);bytes+=document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(bytes>16777216)return new LateReports(successful,false);
                try{
                    var report=codec.model(document,io.ololabs.toolgate.contracts.v2.InvocationResultReport.class);
                    var frame=report.result();
                    if(operation.equals(report.invocationId())&&rows.getString(1).equals(report.fencingToken())
                            &&report.outcome()==io.ololabs.toolgate.contracts.v2.InvocationOutcome.COMPLETED
                            &&frame!=null&&frame.resultType()==io.ololabs.toolgate.contracts.v2.ResultType.complete
                            &&!Boolean.TRUE.equals(frame.isError()))successful.add(report.fencingToken());
                }catch(Failure malformed){
                    if(malformed.status()!=400)throw malformed;
                    // Host refusal/spool observations remain evidence but cannot establish worker success.
                }
            }
            return new LateReports(Set.copyOf(successful),true);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private boolean healthy(EnterpriseInvocation i){
        try{
            boolean local=SqliteState.local(connection);
            String state=local?"json_extract(ep.document,'$.state')":"ep.document::jsonb->>'state'";
            String enabled=local?"json_extract(d.document,'$.enabled')=1":"d.document::jsonb->>'enabled'='true'";
            try(var s=query("SELECT 1 FROM control_endpoints ep JOIN control_records d ON d.tenant_id=ep.tenant_id AND d.kind='DEVICE' AND d.record_id=ep.device_id WHERE ep.tenant_id=? AND ep.device_id=? AND "+state+"='ACTIVE' AND "+enabled,i.evaluation().context().deviceId());var rows=s.executeQuery()){
                return rows.next();
            }
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private boolean scoped(EnterpriseInvocation i,String attempt){
        var scope=enterprise.recoveryScope(attempt);
        return scope!=null&&scope.deviceId().equals(i.evaluation().context().deviceId());
    }
    private static boolean active(EnterpriseInvocation i,String attempt){
        return ACTIVE.contains(i.state().name())&&Objects.equals(i.reservedNonce(),attempt);
    }
    EnterpriseStore.FinalizationPreparation prepare(String operation,long now){
        var i=enterprise.invocation(operation);
        if(i==null)throw Failure.conflict();
        var proof=enterprise.recoveryFacts(operation);var attempts=attempts(operation,now);
        var writes=writes(operation);
        if(!proof.present()||proof.legacy()||!proof.completeJournal()||attempts.size()>64||writes.size()>4096)
            return new EnterpriseStore.FinalizationPreparation(0,0,DirectoryService.digest("incomplete"));
        int closed=0,settled=0;var changes=new ArrayList<String>();boolean healthy=healthy(i);
        for(var a:attempts){
            if(active(i,a.id()))continue;
            if(enterprise.manifestSeal(operation,a.id())==null){
                var seal=enterprise.sealManifest(operation,a.id(),now);closed++;changes.add("seal:"+a.id()+":"+seal.digest());
            }
            if(enterprise.attemptSettlement(operation,a.id())!=null||enterprise.attemptReview(operation,a.id())!=null)continue;
            var own=writes.stream().filter(g->Objects.equals(g.attempt(),a.id())).toList();
            EnterpriseStore.SettlementProof kind=null;
            if(a.consumed()==null&&a.expires()<=now&&own.isEmpty())kind=EnterpriseStore.SettlementProof.NEVER_CONSUMED;
            else if(a.consumed()!=null&&healthy&&scoped(i,a.id())&&own.stream().allMatch(Write::resolved))
                kind=EnterpriseStore.SettlementProof.QUALIFIED_BROKER_ONLY;
            if(kind!=null){
                var record=enterprise.settleAttempt(operation,a.id(),kind,now);settled++;
                changes.add("settled:"+a.id()+":"+record.digest());
            }
        }
        return new EnterpriseStore.FinalizationPreparation(closed,settled,DirectoryService.digest(codec.json(changes)));
    }
    Facts facts(String operation,long now){
        var i=enterprise.invocation(operation);var stored=enterprise.operation(operation);
        if(i==null||stored==null)return new Facts(false,false,false,false,false,List.of(),List.of(),null);
        var bindings=enterprise.recoveryFacts(operation);
        var rows=attempts(operation,now);var writes=writes(operation);var late=lateReports(operation);
        boolean complete=bindings.completeJournal()&&rows.size()<=64&&writes.size()<=4096&&late.complete();
        boolean healthy=healthy(i),conflict=false;
        var attempts=new ArrayList<Attempt>();var manifests=new HashMap<String,Set<String>>();
        for(var a:rows){
            var ids=new TreeSet<>(enterprise.manifestEffects(operation,a.id()));manifests.put(a.id(),ids);
            var seal=enterprise.manifestSeal(operation,a.id());var settlement=enterprise.attemptSettlement(operation,a.id());var review=enterprise.attemptReview(operation,a.id());
            if(seal!=null&&seal.effectCount()!=ids.size())complete=false;
            boolean qualified=review!=null||scoped(i,a.id())&&(healthy||settlement!=null
                    &&settlement.proof()==EnterpriseStore.SettlementProof.QUALIFIED_BROKER_ONLY);
            String reported=!active(i,a.id())&&late.successful().contains(a.id())?"SUCCEEDED":a.state();
            attempts.add(new Attempt(a.id(),reported,a.consumed()!=null,qualified,settlement!=null||review!=null,new Manifest(seal!=null,ids)));
        }
        var effects=new TreeMap<String,List<Generation>>();
        for(var g:writes){
            if(!manifests.containsKey(g.attempt())||!manifests.get(g.attempt()).contains(g.effect()))complete=false;
            if(g.state().equals("MISSING"))complete=false;
            if(g.state().equals("CONFLICT"))conflict=true;
            effects.computeIfAbsent(g.effect(),ignored->new ArrayList<>()).add(g.generation());
        }
        var union=effects.entrySet().stream().map(e->new Effect(e.getKey(),e.getValue(),null,false)).toList();
        return new Facts(true,stored.legacy(),complete,ACTIVE.contains(i.state().name()),conflict,attempts,union,null);
    }
}
