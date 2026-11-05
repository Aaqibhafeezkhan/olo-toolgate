// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind;
import java.sql.*;
import java.util.*;

/** Shared target claims/outcomes. Every replica uses the same database and caller-owned transactions. */
public final class JdbcEffectClaims {
    public record Binding(String tenant,String effectId,long authorizationGeneration,String authNonce,String requestDigest) {
        public Binding {
            for(String value:List.of(tenant,effectId,authNonce))
                if(value.isBlank()||value.length()>128||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("Invalid claim identity");
            if(authorizationGeneration<1||!requestDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid claim binding");
        }
    }
    public enum State { CLAIMED, APPLYING, DONE, FAILED, ABANDONED }
    public record Snapshot(Binding binding,long ownershipGeneration,State state,String resultDigest) {}
    /** Only the insertion winner receives this token; inspect/losing claims never return it. */
    public record Owner(Binding binding,long ownershipGeneration,String claimToken) {
        @Override public String toString(){return "Owner[effectId="+binding.effectId()+", generation="+ownershipGeneration+", token=<redacted>]";}
    }
    public record Acquisition(Snapshot snapshot,Owner owner) {
        public boolean acquired(){return owner!=null;}
    }
    /** Delivery evidence, never permission to apply an effect. */
    public record PendingOutcome(Binding binding,String claimToken,ServicePermitTargetOutcomeKind outcome,String resultDigest) {
        @Override public String toString(){return "PendingOutcome[effectId="+binding.effectId()+", outcome="+outcome+", token=<redacted>]";}
    }
    private static void transaction(Connection c)throws SQLException{
        if(c.getAutoCommit()||c.isReadOnly())throw new IllegalArgumentException("Writable caller transaction required");
    }
    private static void bounded(int limit){if(limit<1||limit>100)throw new IllegalArgumentException("Limit must be 1..100");}
    /** Fresh current schema only; existing unidentified claim stores are never upgraded. */
    public static void initialize(Connection c)throws SQLException {
        transaction(c);
        String schema;
        try{schema=c.getSchema();}catch(SQLFeatureNotSupportedException unsupported){schema=null;}
        var tables=new HashSet<String>();
        try(var rows=c.getMetaData().getTables(c.getCatalog(),schema,"toolgate_target_%",new String[]{"TABLE"})){
            while(rows.next())tables.add(rows.getString("TABLE_NAME"));
        }
        boolean identified=tables.contains("toolgate_target_schema");
        boolean claims=tables.contains("toolgate_target_claims"),outcomes=tables.contains("toolgate_target_outcomes");
        if(!identified&&(claims||outcomes)||identified&&(!claims||!outcomes))
            throw new SQLException("Unsupported target claim schema: a fresh current database is required");
        if(identified)try(var q=c.createStatement();var rows=q.executeQuery("SELECT format FROM toolgate_target_schema")){
            if(!rows.next()||!"toolgate-target-claims-v1".equals(rows.getString(1))||rows.next())
                throw new SQLException("Unsupported target claim schema identity");
        }
        try(var s=c.createStatement()){s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS toolgate_target_claims (
              tenant_id VARCHAR(128) NOT NULL, effect_id VARCHAR(128) NOT NULL,
              authorization_generation BIGINT NOT NULL CHECK(authorization_generation>0),
              auth_nonce VARCHAR(128) NOT NULL, request_digest VARCHAR(71) NOT NULL,
              ownership_generation BIGINT NOT NULL CHECK(ownership_generation>0),
              claim_token VARCHAR(64) NOT NULL,
              state VARCHAR(16) NOT NULL CHECK(state IN ('CLAIMED','APPLYING','DONE','FAILED','ABANDONED')),
              result_digest VARCHAR(71),
              authorization_expires_at BIGINT NOT NULL CHECK(authorization_expires_at>0),
              PRIMARY KEY(tenant_id,effect_id), UNIQUE(tenant_id,auth_nonce),
              CHECK((state IN ('DONE','FAILED') AND result_digest IS NOT NULL)
                 OR (state NOT IN ('DONE','FAILED') AND result_digest IS NULL))
            )
            """);}
        boolean expiry;
        try(var columns=c.getMetaData().getColumns(c.getCatalog(),schema,"toolgate_target_claims","authorization_expires_at")){
            expiry=columns.next();
        }
        if(!expiry)throw new SQLException("Unsupported target claim schema: a fresh current database is required");
        try(var s=c.createStatement()){s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS toolgate_target_outcomes (
              tenant_id VARCHAR(128) NOT NULL, effect_id VARCHAR(128) NOT NULL,
              auth_nonce VARCHAR(128) NOT NULL, claim_token VARCHAR(64) NOT NULL,
              outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('COMPLETED','FAILED')),
              result_digest VARCHAR(71) NOT NULL,
              delivered INTEGER NOT NULL DEFAULT 0 CHECK(delivered IN (0,1)),
              PRIMARY KEY(tenant_id,effect_id),
              FOREIGN KEY(tenant_id,effect_id) REFERENCES toolgate_target_claims(tenant_id,effect_id)
            )
            """);}
        if(!identified)try(var q=c.createStatement()){
            q.executeUpdate("CREATE TABLE toolgate_target_schema(format VARCHAR(64) PRIMARY KEY CHECK(format='toolgate-target-claims-v1'))");
            q.executeUpdate("INSERT INTO toolgate_target_schema(format) VALUES('toolgate-target-claims-v1')");
        }
    }
    public Acquisition claim(Connection c,Binding b,long authorizationExpiresAt)throws SQLException{
        transaction(c);if(authorizationExpiresAt<=0)throw new IllegalArgumentException("Invalid expiry");
        String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        boolean inserted;
        try(var s=c.prepareStatement("""
            INSERT INTO toolgate_target_claims(tenant_id,effect_id,authorization_generation,auth_nonce,
              request_digest,ownership_generation,claim_token,state,authorization_expires_at)
            VALUES(?,?,?,?,?,1,?,'CLAIMED',?) ON CONFLICT DO NOTHING
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());s.setLong(3,b.authorizationGeneration());
            s.setString(4,b.authNonce());s.setString(5,b.requestDigest());s.setString(6,token);s.setLong(7,authorizationExpiresAt);
            inserted=s.executeUpdate()==1;
        }
        Snapshot snapshot=inspect(c,b.tenant(),b.effectId());
        if(snapshot==null||!snapshot.binding().equals(b))throw new SecurityException("Effect claim binding conflict");
        return new Acquisition(snapshot,inserted?new Owner(b,1,token):null);
    }
    private Snapshot snapshot(ResultSet rows,String tenant,String effectId,int offset)throws SQLException{
        return new Snapshot(new Binding(tenant,effectId,rows.getLong(offset),rows.getString(offset+1),rows.getString(offset+2)),
            rows.getLong(offset+3),State.valueOf(rows.getString(offset+4)),rows.getString(offset+5));
    }
    public Snapshot inspect(Connection c,String tenant,String effectId)throws SQLException{
        transaction(c);
        try(var s=c.prepareStatement("""
            SELECT authorization_generation,auth_nonce,request_digest,ownership_generation,state,result_digest
            FROM toolgate_target_claims WHERE tenant_id=? AND effect_id=?
            """)){
            s.setString(1,tenant);s.setString(2,effectId);
            try(var rows=s.executeQuery()){return rows.next()?snapshot(rows,tenant,effectId,1):null;}
        }
    }
    public List<Snapshot> expiredClaims(Connection c,String tenant,long now,int limit)throws SQLException{
        transaction(c);bounded(limit);if(now<0)throw new IllegalArgumentException("Invalid time");
        try(var s=c.prepareStatement("""
            SELECT effect_id,authorization_generation,auth_nonce,request_digest,ownership_generation,state,result_digest
            FROM toolgate_target_claims WHERE tenant_id=? AND state='CLAIMED'
              AND authorization_expires_at>0 AND authorization_expires_at<=?
            ORDER BY authorization_expires_at,effect_id LIMIT ?
            """)){
            s.setString(1,tenant);s.setLong(2,now);s.setInt(3,limit);var result=new ArrayList<Snapshot>();
            try(var rows=s.executeQuery()){while(rows.next())result.add(snapshot(rows,tenant,rows.getString(1),2));}
            return List.copyOf(result);
        }
    }
    /** Commit before external actions; a subsequent business transaction may atomically finish with its mutation. */
    public boolean beginApply(Connection c,Owner owner)throws SQLException{
        return transition(c,owner,"CLAIMED","APPLYING",null);
    }
    /** Reporting not-started is possible only after this CAS and its outbox record commit. */
    public boolean abandon(Connection c,Snapshot expected)throws SQLException{
        transaction(c);Objects.requireNonNull(expected);if(expected.state()!=State.CLAIMED)return false;
        Binding b=expected.binding();boolean changed;
        try(var s=c.prepareStatement("""
            UPDATE toolgate_target_claims SET state='ABANDONED',ownership_generation=ownership_generation+1
            WHERE tenant_id=? AND effect_id=? AND authorization_generation=? AND auth_nonce=?
              AND request_digest=? AND ownership_generation=? AND state='CLAIMED'
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());s.setLong(3,b.authorizationGeneration());
            s.setString(4,b.authNonce());s.setString(5,b.requestDigest());s.setLong(6,expected.ownershipGeneration());
            changed=s.executeUpdate()==1;
        }
        if(changed){
            String token;
            try(var s=c.prepareStatement("SELECT claim_token FROM toolgate_target_claims WHERE tenant_id=? AND effect_id=?")){
                s.setString(1,b.tenant());s.setString(2,b.effectId());
                try(var rows=s.executeQuery()){if(!rows.next())throw new SQLException("Claim disappeared");token=rows.getString(1);}
            }
            enqueue(c,b,token,ServicePermitTargetOutcomeKind.FAILED,notStartedDigest());
        }
        return changed;
    }
    private static String notStartedDigest(){
        try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("toolgate-target-not-started-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** Mutation, terminal claim and durable delivery intent must share the caller's target transaction. */
    public boolean finish(Connection c,Owner owner,boolean success,String resultDigest)throws SQLException{
        if(resultDigest==null||!resultDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Result digest required");
        boolean changed=transition(c,owner,"APPLYING",success?"DONE":"FAILED",resultDigest);
        if(changed)enqueue(c,owner.binding(),owner.claimToken(),
            success?ServicePermitTargetOutcomeKind.COMPLETED:ServicePermitTargetOutcomeKind.FAILED,resultDigest);
        return changed;
    }
    private void enqueue(Connection c,Binding b,String token,ServicePermitTargetOutcomeKind outcome,String result)throws SQLException{
        try(var s=c.prepareStatement("""
            INSERT INTO toolgate_target_outcomes(tenant_id,effect_id,auth_nonce,claim_token,outcome,result_digest,delivered)
            VALUES(?,?,?,?,?,?,0)
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());s.setString(3,b.authNonce());s.setString(4,token);
            s.setString(5,outcome.name());s.setString(6,result);if(s.executeUpdate()!=1)throw new SQLException("Outcome not retained");
        }
    }
    private PendingOutcome outcome(ResultSet rows,String tenant)throws SQLException{
        return new PendingOutcome(new Binding(tenant,rows.getString(1),rows.getLong(2),rows.getString(3),rows.getString(4)),
            rows.getString(5),ServicePermitTargetOutcomeKind.valueOf(rows.getString(6)),rows.getString(7));
    }
    public List<PendingOutcome> pendingOutcomes(Connection c,String tenant,int limit)throws SQLException{
        return pendingOutcomes(c,tenant,null,limit);
    }
    /** Keyset pagination keeps permanently deferred evidence from starving later outcomes. */
    public List<PendingOutcome> pendingOutcomes(Connection c,String tenant,String afterEffectId,int limit)throws SQLException{
        transaction(c);bounded(limit);
        String sql="""
            SELECT c.effect_id,c.authorization_generation,c.auth_nonce,c.request_digest,o.claim_token,o.outcome,o.result_digest
            FROM toolgate_target_outcomes o JOIN toolgate_target_claims c
              ON c.tenant_id=o.tenant_id AND c.effect_id=o.effect_id
            WHERE o.tenant_id=? AND o.delivered=0
            """+(afterEffectId==null?"":" AND o.effect_id>? ")+" ORDER BY o.effect_id LIMIT ?";
        try(var s=c.prepareStatement(sql)){
            int index=1;s.setString(index++,tenant);if(afterEffectId!=null)s.setString(index++,afterEffectId);
            s.setInt(index,limit);var result=new ArrayList<PendingOutcome>();
            try(var rows=s.executeQuery()){while(rows.next())result.add(outcome(rows,tenant));}
            return List.copyOf(result);
        }
    }
    public PendingOutcome pendingOutcome(Connection c,Binding b)throws SQLException{
        transaction(c);
        try(var s=c.prepareStatement("""
            SELECT c.effect_id,c.authorization_generation,c.auth_nonce,c.request_digest,o.claim_token,o.outcome,o.result_digest
            FROM toolgate_target_outcomes o JOIN toolgate_target_claims c
              ON c.tenant_id=o.tenant_id AND c.effect_id=o.effect_id
            WHERE o.tenant_id=? AND o.effect_id=? AND o.delivered=0
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());
            try(var rows=s.executeQuery()){
                if(!rows.next())return null;var found=outcome(rows,b.tenant());
                if(!found.binding().equals(b))throw new SecurityException("Outcome binding conflict");
                return found;
            }
        }
    }
    public boolean acknowledgeOutcome(Connection c,PendingOutcome outcome)throws SQLException{
        transaction(c);var b=outcome.binding();
        try(var s=c.prepareStatement("""
            UPDATE toolgate_target_outcomes SET delivered=1
            WHERE tenant_id=? AND effect_id=? AND auth_nonce=? AND claim_token=? AND outcome=? AND result_digest=? AND delivered=0
              AND EXISTS(SELECT 1 FROM toolgate_target_claims c
                WHERE c.tenant_id=toolgate_target_outcomes.tenant_id AND c.effect_id=toolgate_target_outcomes.effect_id
                  AND c.authorization_generation=? AND c.request_digest=?)
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());s.setString(3,b.authNonce());s.setString(4,outcome.claimToken());
            s.setString(5,outcome.outcome().name());s.setString(6,outcome.resultDigest());
            s.setLong(7,b.authorizationGeneration());s.setString(8,b.requestDigest());return s.executeUpdate()==1;
        }
    }
    private boolean transition(Connection c,Owner o,String from,String to,String result)throws SQLException{
        transaction(c);Objects.requireNonNull(o);Binding b=o.binding();
        try(var s=c.prepareStatement("""
            UPDATE toolgate_target_claims SET state=?,result_digest=?
            WHERE tenant_id=? AND effect_id=? AND authorization_generation=? AND auth_nonce=?
              AND request_digest=? AND ownership_generation=? AND claim_token=? AND state=?
            """)){
            s.setString(1,to);s.setString(2,result);s.setString(3,b.tenant());s.setString(4,b.effectId());
            s.setLong(5,b.authorizationGeneration());s.setString(6,b.authNonce());s.setString(7,b.requestDigest());
            s.setLong(8,o.ownershipGeneration());s.setString(9,o.claimToken());s.setString(10,from);
            return s.executeUpdate()==1;
        }
    }
}
