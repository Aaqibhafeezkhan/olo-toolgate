// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import java.sql.*;
import java.util.*;

/**
 * Shared target-side claim store. Every replica must use the same database.
 * Methods use the caller's transaction; they never commit or perform a business effect.
 */
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
    /** A token is returned only to the unique insertion winner, never by inspect or a losing claim. */
    public record Owner(Binding binding,long ownershipGeneration,String claimToken) {}
    public record Acquisition(Snapshot snapshot,Owner owner) {
        public boolean acquired(){return owner!=null;}
    }
    private static void transaction(Connection connection)throws SQLException{
        if(connection.getAutoCommit()||connection.isReadOnly())throw new IllegalArgumentException("Writable caller transaction required");
    }
    /** Run via the target's deployment migration, before accepting requests. */
    public static void initialize(Connection c)throws SQLException {
        transaction(c);
        try(var s=c.createStatement()){s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS toolgate_target_claims (
              tenant_id VARCHAR(128) NOT NULL, effect_id VARCHAR(128) NOT NULL,
              authorization_generation BIGINT NOT NULL CHECK(authorization_generation>0),
              auth_nonce VARCHAR(128) NOT NULL, request_digest VARCHAR(71) NOT NULL,
              ownership_generation BIGINT NOT NULL CHECK(ownership_generation>0),
              claim_token VARCHAR(64) NOT NULL,
              state VARCHAR(16) NOT NULL CHECK(state IN ('CLAIMED','APPLYING','DONE','FAILED','ABANDONED')),
              result_digest VARCHAR(71),
              PRIMARY KEY(tenant_id,effect_id),
              UNIQUE(tenant_id,auth_nonce),
              CHECK((state IN ('DONE','FAILED') AND result_digest IS NOT NULL)
                 OR (state NOT IN ('DONE','FAILED') AND result_digest IS NULL))
            )
            """);}
    }
    public Acquisition claim(Connection c,Binding b)throws SQLException{
        transaction(c);String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        boolean inserted;
        try(var s=c.prepareStatement("""
            INSERT INTO toolgate_target_claims(tenant_id,effect_id,authorization_generation,auth_nonce,
              request_digest,ownership_generation,claim_token,state)
            VALUES(?,?,?,?,?,1,?,'CLAIMED') ON CONFLICT DO NOTHING
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());s.setLong(3,b.authorizationGeneration());
            s.setString(4,b.authNonce());s.setString(5,b.requestDigest());s.setString(6,token);
            inserted=s.executeUpdate()==1;
        }
        Snapshot snapshot=inspect(c,b.tenant(),b.effectId());
        if(snapshot==null||!snapshot.binding().equals(b))throw new SecurityException("Effect claim binding conflict");
        return new Acquisition(snapshot,inserted?new Owner(b,1,token):null);
    }
    public Snapshot inspect(Connection c,String tenant,String effectId)throws SQLException{
        transaction(c);
        try(var s=c.prepareStatement("""
            SELECT authorization_generation,auth_nonce,request_digest,ownership_generation,state,result_digest
            FROM toolgate_target_claims WHERE tenant_id=? AND effect_id=?
            """)){
            s.setString(1,tenant);s.setString(2,effectId);
            try(var rows=s.executeQuery()){
                if(!rows.next())return null;
                return new Snapshot(new Binding(tenant,effectId,rows.getLong(1),rows.getString(2),rows.getString(3)),
                    rows.getLong(4),State.valueOf(rows.getString(5)),rows.getString(6));
            }
        }
    }
    /** Must succeed before any business action. Prefer CAS, action and finish in one transaction. */
    public boolean beginApply(Connection c,Owner owner)throws SQLException{
        return transition(c,owner,"CLAIMED","APPLYING",null,false);
    }
    /** Recovery can prove not-started only by committing this CAS before reporting abandonment. */
    public boolean abandon(Connection c,Snapshot expected)throws SQLException{
        transaction(c);Objects.requireNonNull(expected);
        if(expected.state()!=State.CLAIMED)return false;
        Binding b=expected.binding();
        try(var s=c.prepareStatement("""
            UPDATE toolgate_target_claims SET state='ABANDONED',ownership_generation=ownership_generation+1
            WHERE tenant_id=? AND effect_id=? AND authorization_generation=? AND auth_nonce=?
              AND request_digest=? AND ownership_generation=? AND state='CLAIMED'
            """)){
            s.setString(1,b.tenant());s.setString(2,b.effectId());s.setLong(3,b.authorizationGeneration());
            s.setString(4,b.authNonce());s.setString(5,b.requestDigest());s.setLong(6,expected.ownershipGeneration());
            return s.executeUpdate()==1;
        }
    }
    /** Recorded result and business mutation should commit atomically in the target database. */
    public boolean finish(Connection c,Owner owner,boolean success,String resultDigest)throws SQLException{
        if(resultDigest==null||!resultDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Result digest required");
        return transition(c,owner,"APPLYING",success?"DONE":"FAILED",resultDigest,false);
    }
    private boolean transition(Connection c,Owner o,String from,String to,String result,boolean advance)throws SQLException{
        transaction(c);Objects.requireNonNull(o);Binding b=o.binding();
        try(var s=c.prepareStatement("""
            UPDATE toolgate_target_claims SET state=?,result_digest=?,ownership_generation=ownership_generation+?
            WHERE tenant_id=? AND effect_id=? AND authorization_generation=? AND auth_nonce=?
              AND request_digest=? AND ownership_generation=? AND claim_token=? AND state=?
            """)){
            s.setString(1,to);s.setString(2,result);s.setInt(3,advance?1:0);
            s.setString(4,b.tenant());s.setString(5,b.effectId());s.setLong(6,b.authorizationGeneration());
            s.setString(7,b.authNonce());s.setString(8,b.requestDigest());s.setLong(9,o.ownershipGeneration());
            s.setString(10,o.claimToken());s.setString(11,from);return s.executeUpdate()==1;
        }
    }
}
