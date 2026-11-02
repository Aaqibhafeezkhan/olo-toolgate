// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import java.sql.*;
import java.util.*;

/** Durable effect generations; all operations share Control's existing tenant writer transaction. */
final class JdbcEffectStore implements EffectStore {
    private final Connection connection;private final String tenant;
    JdbcEffectStore(Connection connection,String tenant){this.connection=connection;this.tenant=tenant;}
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);s.setString(1,tenant);for(int n=0;n<values.length;n++)s.setObject(n+2,values[n]);return s;
    }
    public Intent intent(String effectId){
        try(var s=query("SELECT request_digest,arguments_digest,profile_digest,classification,document,recorded_at FROM control_effect_intents WHERE tenant_id=? AND effect_id=?",effectId);var rows=s.executeQuery()){
            return rows.next()?new Intent(effectId,rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getLong(6)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void recordIntent(Intent intent){
        text(intent.effectId());digest(intent.requestDigest());digest(intent.argumentsDigest());digest(intent.profileDigest());
        var existing=intent(intent.effectId());
        if(existing!=null){if(!existing.equals(intent))throw Failure.conflict();return;}
        try(var s=query("INSERT INTO control_effect_intents(tenant_id,effect_id,request_digest,arguments_digest,profile_digest,classification,document,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                intent.effectId(),intent.requestDigest(),intent.argumentsDigest(),intent.profileDigest(),intent.classification(),intent.document(),intent.recordedAt())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public Generation byNonce(String nonce){
        try(var s=query("SELECT e.effect_id,e.operation_id,g.attempt_id,e.route_id,e.request_digest,g.device_id,g.broker_id,g.handler_id,g.auth_nonce,g.expires_at,g.outcome_deadline,g.generation,g.state,g.revision,g.consumer,g.claim_token FROM control_effect_generations g JOIN control_effects e ON e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id WHERE g.tenant_id=? AND g.auth_nonce=?",nonce);var rows=s.executeQuery()){
            if(!rows.next())return null;
            var b=new Binding(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getString(8),rows.getString(9),rows.getLong(10),rows.getLong(11));
            return new Generation(b,rows.getLong(12),rows.getString(13),rows.getLong(14),rows.getString(15),rows.getString(16));
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public Generation current(String id){
        try(var s=query("SELECT g.auth_nonce FROM control_effects e JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation WHERE e.tenant_id=? AND e.effect_id=?",id);var rows=s.executeQuery()){return rows.next()?byNonce(rows.getString(1)):null;}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    private static void text(String s){if(s==null||s.isBlank()||s.length()>128||s.chars().anyMatch(c->c<32||c==127))throw Failure.validation();}
    private static void digest(String s){if(s==null||!s.matches("sha256:[a-f0-9]{64}"))throw Failure.validation();}
    public Authorization authorize(Binding b,long now){
        for(var s:Arrays.asList(b.effectId(),b.operationId(),b.attemptId(),b.routeId(),b.deviceId(),b.brokerId(),b.handlerId(),b.authNonce()))text(s);
        digest(b.requestDigest());
        if(now<0||b.expiresAt()<=now||b.expiresAt()-now>15000||b.outcomeDeadline()<b.expiresAt()||b.outcomeDeadline()-now>600000)throw Failure.validation();
        expire(now);var old=current(b.effectId());long generation=1;
        if(old!=null){
            var a=old.binding();
            if(!a.operationId().equals(b.operationId())||!a.routeId().equals(b.routeId())||!a.requestDigest().equals(b.requestDigest()))throw Failure.conflict();
            if(!old.state().equals("RELEASED")){
                if(!a.deviceId().equals(b.deviceId())||!a.brokerId().equals(b.brokerId())||!a.handlerId().equals(b.handlerId())||!a.attemptId().equals(b.attemptId()))throw ManagementAccess.denied();
                return new Authorization(old,false);
            }
            if(a.authNonce().equals(b.authNonce()))throw Failure.conflict();generation=old.generation()+1;
        }
        try{
            if(old==null)try(var s=query("INSERT INTO control_effects(tenant_id,effect_id,operation_id,route_id,request_digest,current_generation) VALUES(?,?,?,?,?,1)",b.effectId(),b.operationId(),b.routeId(),b.requestDigest())){s.executeUpdate();}
            else try(var s=connection.prepareStatement("UPDATE control_effects SET current_generation=? WHERE tenant_id=? AND effect_id=? AND current_generation=?")){s.setLong(1,generation);s.setString(2,tenant);s.setString(3,b.effectId());s.setLong(4,old.generation());if(s.executeUpdate()!=1)throw Failure.conflict();}
            try(var s=query("INSERT INTO control_effect_generations(tenant_id,effect_id,generation,operation_id,attempt_id,auth_nonce,device_id,broker_id,handler_id,state,issued_at,expires_at,outcome_deadline,revision) VALUES(?,?,?,?,?,?,?,?,?,'AUTHORIZED',?,?,?,1)",b.effectId(),generation,b.operationId(),b.attemptId(),b.authNonce(),b.deviceId(),b.brokerId(),b.handlerId(),now,b.expiresAt(),b.outcomeDeadline())){s.executeUpdate();}
            return new Authorization(byNonce(b.authNonce()),true);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private Generation require(String nonce){var g=byNonce(nonce);if(g==null)throw ManagementAccess.denied();return g;}
    private void live(Generation g,long now){var c=current(g.binding().effectId());if(c==null||c.generation()!=g.generation()||g.binding().expiresAt()<=now)throw ManagementAccess.denied();}
    private Generation change(Generation g,String state,String consumer,String claim){
        try(var s=connection.prepareStatement("UPDATE control_effect_generations SET state=?,consumer=?,claim_token=?,revision=revision+1 WHERE tenant_id=? AND effect_id=? AND generation=? AND revision=?")){
            s.setString(1,state);s.setString(2,consumer);s.setString(3,claim);s.setString(4,tenant);s.setString(5,g.binding().effectId());s.setLong(6,g.generation());s.setLong(7,g.revision());if(s.executeUpdate()!=1)throw Failure.conflict();return byNonce(g.binding().authNonce());
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private Generation owner(String nonce,String device,String broker,String handler,long now){var g=require(nonce);var b=g.binding();
        if(!b.deviceId().equals(device)||!b.brokerId().equals(broker)||!b.handlerId().equals(handler))throw ManagementAccess.denied();live(g,now);return g;}
    public Generation release(String nonce,String device,String broker,String handler,long now){var g=owner(nonce,device,broker,handler,now);if(!g.state().equals("AUTHORIZED"))throw Failure.conflict();return change(g,"RELEASED",null,null);}
    public Generation sending(String nonce,String device,String broker,String handler,long now){var g=owner(nonce,device,broker,handler,now);if(!g.state().equals("AUTHORIZED"))throw Failure.conflict();return change(g,"SENDING",null,null);}
    public Authorization consume(String nonce,String requestDigest,String consumer,String claim,long now){text(consumer);text(claim);var g=require(nonce);
        if(!g.binding().requestDigest().equals(requestDigest))throw ManagementAccess.denied();
        if(g.consumer()!=null){if(!g.consumer().equals(consumer)||!g.claimToken().equals(claim))throw ManagementAccess.denied();return new Authorization(g,false);}
        live(g,now);if(!Set.of("AUTHORIZED","SENDING").contains(g.state()))throw Failure.conflict();
        return new Authorization(change(g,"CONSUMED",consumer,claim),true);
    }
    private void evidence(Generation g,String source,String outcome,String result,long now){
        if(!Set.of("COMPLETED","FAILED").contains(outcome))throw Failure.validation();digest(result);if(now<0)throw Failure.validation();
        String hash=DirectoryService.digest(source+"\n"+outcome+"\n"+result);
        try(var s=query("INSERT INTO control_effect_evidence(tenant_id,effect_id,generation,evidence_digest,source,outcome,result_digest,recorded_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",g.binding().effectId(),g.generation(),hash,source,outcome,result,now)){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public void observe(String nonce,String outcome,String result,long now){evidence(require(nonce),"BROKER",outcome,result,now);}
    public Generation targetOutcome(String nonce,String consumer,String claim,String outcome,String result,long now){
        var g=require(nonce);if(g.consumer()==null||!g.consumer().equals(consumer)||!g.claimToken().equals(claim))throw ManagementAccess.denied();
        evidence(g,"TARGET:"+consumer,outcome,result,now);
        boolean conflict;
        try(var s=query("SELECT 1 FROM control_effect_evidence WHERE tenant_id=? AND effect_id=? AND generation=? AND source=? AND (outcome<>? OR result_digest<>?)",g.binding().effectId(),g.generation(),"TARGET:"+consumer,outcome,result);var rows=s.executeQuery()){conflict=rows.next();}
        catch(SQLException failure){throw Failure.unavailable();}
        if(g.state().equals("CONFLICT"))return g;
        if(conflict){if(!Set.of("COMPLETED","FAILED").contains(g.state()))throw Failure.conflict();return change(g,"CONFLICT",consumer,claim);}
        if(g.state().equals(outcome))return g;
        if(!Set.of("CONSUMED","AMBIGUOUS").contains(g.state()))throw Failure.conflict();
        return change(g,outcome,consumer,claim);
    }
    /** This positive closure is available only for a qualified, v2-leased complete-mediation attempt. */
    private static final String CLOSED_BEFORE_SEND="CASE WHEN state='AUTHORIZED' AND EXISTS("
            +"SELECT 1 FROM control_attempt_recovery_scopes s JOIN control_execution_leases l "
            +"ON l.tenant_id=s.tenant_id AND l.invocation_id=s.operation_id AND l.fencing_token=s.attempt_id "
            +"WHERE s.tenant_id=control_effect_generations.tenant_id AND s.operation_id=control_effect_generations.operation_id "
            +"AND s.attempt_id=control_effect_generations.attempt_id AND s.device_id=control_effect_generations.device_id "
            +"AND l.device_id=s.device_id) THEN 'RELEASED' ELSE 'AMBIGUOUS' END";
    public void abandon(String operationId){
        try(var s=query("UPDATE control_effect_generations SET state="+CLOSED_BEFORE_SEND+",revision=revision+1 WHERE tenant_id=? AND operation_id=? AND state IN('AUTHORIZED','SENDING','CONSUMED')",operationId)){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public int expire(long now){
        try(var s=query("UPDATE control_effect_generations SET state="+CLOSED_BEFORE_SEND+",revision=revision+1 WHERE tenant_id=? AND (effect_id,generation) IN (SELECT effect_id,generation FROM control_effect_generations WHERE tenant_id=? AND ((state='AUTHORIZED' AND expires_at<=?) OR (state IN('SENDING','CONSUMED') AND outcome_deadline<=?)) ORDER BY expires_at LIMIT 100)",tenant,now,now)){return s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
}
