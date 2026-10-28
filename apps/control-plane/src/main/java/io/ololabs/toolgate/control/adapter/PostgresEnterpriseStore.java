// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import java.sql.*;
import java.util.*;

/** SQL compare-and-swap and tenant serialization protect replica/restart execution boundaries. */
final class PostgresEnterpriseStore implements EnterpriseStore {
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
    public List<EnterpriseInvocation> expiredInvocations(long now,int limit){try(var s=query("SELECT document FROM control_enterprise_invocations WHERE tenant_id=? AND expires_at<=? AND state IN('PENDING_APPROVAL','QUEUED','RESERVED','EXECUTING') ORDER BY invocation_id LIMIT ?",now,limit);var rows=s.executeQuery()){var values=new ArrayList<EnterpriseInvocation>();while(rows.next())values.add(codec.model(rows.getString(1),EnterpriseInvocation.class));return List.copyOf(values);}catch(SQLException failure){throw Failure.unavailable();}}
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
    public void reserveNonce(Nonce value){try(var s=query("INSERT INTO control_enterprise_nonces(tenant_id,nonce,invocation_id,evaluation_digest,authorization_epoch,issued_at,expires_at) VALUES(?,?,?,?,?,?,?)",value.id(),value.invocationId(),value.evaluationDigest(),value.authorizationEpoch(),value.issuedAt(),value.expiresAt())){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public void consumeNonce(String id,long now){try(var s=connection.prepareStatement("UPDATE control_enterprise_nonces SET consumed_at=? WHERE tenant_id=? AND nonce=? AND consumed_at IS NULL AND expires_at>?")){s.setLong(1,now);s.setString(2,tenant);s.setString(3,id);s.setLong(4,now);if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
    private <T> String checked(T value,Class<T> model){String json=codec.json(value);if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw Failure.validation();codec.model(json,model);return json;}
}
