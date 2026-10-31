// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.EnterpriseStore;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import java.sql.*;
import java.util.*;

/** Operation/attempt journal, always in the invocation's existing tenant transaction (ADR 035). */
final class JdbcOperationLedger {
    private final Connection connection;
    private final String tenant;
    JdbcOperationLedger(Connection connection,String tenant){this.connection=connection;this.tenant=tenant;}
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);s.setString(1,tenant);
        for(int n=0;n<values.length;n++)s.setObject(n+2,values[n]);return s;
    }
    EnterpriseStore.Operation operation(String id){
        try(var s=query("SELECT state,legacy,revision FROM control_operations WHERE tenant_id=? AND operation_id=?",id);var rows=s.executeQuery()){
            return rows.next()?new EnterpriseStore.Operation(id,rows.getString(1),rows.getBoolean(2),rows.getLong(3)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    void reserve(EnterpriseStore.Nonce nonce){
        try(var s=query("INSERT INTO control_operation_attempts(tenant_id,attempt_id,operation_id,evaluation_digest,authorization_epoch,issued_at,expires_at) VALUES(?,?,?,?,?,?,?)",nonce.id(),nonce.invocationId(),nonce.evaluationDigest(),nonce.authorizationEpoch(),nonce.issuedAt(),nonce.expiresAt())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    void observe(EnterpriseInvocation value,String document,long now,boolean newInvocation){
        var previous=operation(value.id());
        if(previous==null&&!newInvocation)throw Failure.unavailable();
        boolean active=switch(value.state()){case PENDING_APPROVAL,QUEUED,RESERVED,DISPATCHED,EXECUTING->true;default->false;};
        if(!active)new JdbcEffectStore(connection,tenant).abandon(value.id());
        String state="OUTCOME_UNKNOWN";
        if(active)state="RUNNING";
        else if((previous==null||!previous.legacy())&&Set.of("FAILED","CANCELLED","EXPIRED").contains(value.state().name())){
            // Absence of a worker report is never proof. Only Control's complete, unconsumed permit ledger qualifies.
            try(var s=query("SELECT count(*),COALESCE(sum(CASE WHEN n.consumed_at IS NOT NULL OR a.attempt_id IS NULL THEN 1 ELSE 0 END),0) FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce WHERE n.tenant_id=? AND n.invocation_id=? AND n.purpose<>'SERVICE'",value.id());var rows=s.executeQuery()){
                rows.next();long permits=rows.getLong(1),uncertain=rows.getLong(2);
                boolean bound=value.reservedNonce()==null;
                if(!bound)try(var q=query("SELECT 1 FROM control_operation_attempts WHERE tenant_id=? AND operation_id=? AND attempt_id=?",value.id(),value.reservedNonce());var found=q.executeQuery()){bound=found.next();}
                if(uncertain==0&&bound&&(permits>0||value.reservedNonce()==null))state="FAILED";
            }catch(SQLException failure){throw Failure.unavailable();}
        }
        try{
            if(previous==null)try(var s=query("INSERT INTO control_operations(tenant_id,operation_id,request_digest,state,legacy,revision,created_at) VALUES(?,?,?,?,?,1,?)",value.id(),value.requestDigest(),state,false,now)){s.executeUpdate();}
            else try(var s=connection.prepareStatement("UPDATE control_operations SET state=?,revision=revision+1 WHERE tenant_id=? AND operation_id=? AND revision=?")){
                s.setString(1,state);s.setString(2,tenant);s.setString(3,value.id());s.setLong(4,previous.revision());if(s.executeUpdate()!=1)throw Failure.conflict();
            }
            if(value.reservedNonce()!=null)try(var s=connection.prepareStatement("INSERT INTO control_attempt_snapshots(tenant_id,operation_id,attempt_id,revision,state,document) SELECT tenant_id,operation_id,attempt_id,?,?,? FROM control_operation_attempts WHERE tenant_id=? AND operation_id=? AND attempt_id=?")){
                s.setLong(1,value.revision());s.setString(2,value.state().name());s.setString(3,document);s.setString(4,tenant);s.setString(5,value.id());s.setString(6,value.reservedNonce());s.executeUpdate();
            }
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    List<EnterpriseStore.Attempt> attempts(String id,long now){
        var result=new ArrayList<EnterpriseStore.Attempt>();
        try(var s=query("SELECT a.attempt_id,n.consumed_at,a.issued_at,a.expires_at,s.state,s.revision,(SELECT count(*) FROM control_execution_evidence e WHERE e.tenant_id=a.tenant_id AND e.invocation_id=a.operation_id AND e.fencing_token=a.attempt_id) FROM control_operation_attempts a JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id LEFT JOIN control_attempt_snapshots s ON s.tenant_id=a.tenant_id AND s.attempt_id=a.attempt_id AND s.revision=(SELECT max(h.revision) FROM control_attempt_snapshots h WHERE h.tenant_id=a.tenant_id AND h.attempt_id=a.attempt_id) WHERE a.tenant_id=? AND a.operation_id=? ORDER BY a.issued_at,a.attempt_id",id);var rows=s.executeQuery()){
            while(rows.next()){
                String attempt=rows.getString(1);long consumed=rows.getLong(2);Long consumedAt=rows.wasNull()?null:consumed;
                long issued=rows.getLong(3),expires=rows.getLong(4);String state=rows.getString(5);long revision=rows.getLong(6);
                if(state==null)state=consumedAt!=null?"OUTCOME_UNKNOWN":expires<=now?"EXPIRED":"RESERVED";
                else if(consumedAt==null&&expires<=now&&Set.of("RESERVED","DISPATCHED").contains(state))state="EXPIRED";
                else if(consumedAt!=null&&Set.of("RESERVED","DISPATCHED","EXPIRED").contains(state))state="OUTCOME_UNKNOWN";
                result.add(new EnterpriseStore.Attempt(attempt,id,state,consumedAt,issued,expires,revision,rows.getLong(7)));
            }
            return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
}
