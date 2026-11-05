// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ControlErrorDetailCode;
import io.ololabs.toolgate.contracts.v2.EffectConsentPreview;
import io.ololabs.toolgate.control.application.*;
import java.sql.*;
import java.util.List;

/** Immutable consent and per-identity use records under the existing serialized tenant writer transaction. */
final class JdbcEffectConsentStore implements EffectConsentStore {
    private final Connection connection;private final String tenant;private final Codec codec;
    JdbcEffectConsentStore(Connection connection,String tenant,Codec codec){this.connection=connection;this.tenant=tenant;this.codec=codec;}
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);s.setString(1,tenant);
        for(int n=0;n<values.length;n++)s.setObject(n+2,values[n]);return s;
    }
    private Pending find(String column,String id){
        try(var s=query("SELECT consent_id,operation_id,preview_digest,document,not_before,expires_at FROM control_effect_consents WHERE tenant_id=? AND "+column+"=?",id);var r=s.executeQuery()){
            return r.next()?new Pending(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getLong(6)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public Pending byId(String id){return find("consent_id",id);}
    public Pending forOperation(String id){return find("operation_id",id);}
    public void save(EffectConsentPreviews.Snapshot snapshot){
        var preview=codec.model(snapshot.document(),EffectConsentPreview.class);
        if(!preview.tenantId().equals(tenant)||!Canonical.arguments((JsonNode)codec.value(snapshot.document())).equals(snapshot.digest())
            ||preview.expiresAtUnixMs()<=preview.notBeforeUnixMs()||preview.expiresAtUnixMs()!=snapshot.expiresAtUnixMs())throw Failure.validation();
        var previous=forOperation(preview.operationId());
        if(previous!=null){if(!previous.consentId().equals(preview.consentId())||!previous.digest().equals(snapshot.digest()))throw Failure.conflict();return;}
        var seen=new java.util.HashSet<Long>();
        for(var slot:preview.slots())if(!seen.add(slot.slotIndex())||slot.slotIndex()>=preview.slots().size())throw Failure.validation();
        try{
            try(var s=query("INSERT INTO control_effect_consents(tenant_id,consent_id,operation_id,preview_digest,document,not_before,expires_at) VALUES(?,?,?,?,?,?,?)",
                preview.consentId(),preview.operationId(),snapshot.digest(),snapshot.document(),preview.notBeforeUnixMs(),preview.expiresAtUnixMs())){s.executeUpdate();}
            for(var slot:preview.slots())try(var s=query("INSERT INTO control_effect_consent_slots(tenant_id,consent_id,slot_index,route_id,mapped_digest,permitted_count) VALUES(?,?,?,?,?,?)",
                preview.consentId(),slot.slotIndex(),slot.request().routeId(),Canonical.arguments((JsonNode)codec.value(codec.json(slot.request()))),slot.permittedCount())){s.executeUpdate();}
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public Approval approval(String id){
        try(var s=query("SELECT reviewer,authorization_epoch,approved_at FROM control_effect_consent_approvals WHERE tenant_id=? AND consent_id=?",id);var r=s.executeQuery()){
            return r.next()?new Approval(r.getString(1),r.getLong(2),r.getLong(3)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public boolean revoked(String id){
        try(var s=query("SELECT 1 FROM control_effect_consent_revocations WHERE tenant_id=? AND consent_id=?",id);var r=s.executeQuery()){return r.next();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    private Pending valid(String id,String digest,long now){
        var p=byId(id);
        if(p==null||!p.digest().equals(digest)||now<p.notBefore()||now>=p.expiresAt()||revoked(id))throw mismatch();
        return p;
    }
    public void approve(String id,String digest,String reviewer,long epoch,long now){
        valid(id,digest,now);
        if(reviewer==null||reviewer.isBlank()||reviewer.length()>128||epoch<0)throw Failure.validation();
        var old=approval(id);
        if(old!=null){if(!old.reviewer().equals(reviewer)||old.authorizationEpoch()!=epoch)throw Failure.conflict();return;}
        try(var s=query("INSERT INTO control_effect_consent_approvals(tenant_id,consent_id,reviewer,authorization_epoch,approved_at) VALUES(?,?,?,?,?)",
            id,reviewer,epoch,now)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}
    }
    public void revoke(String id,String reviewer,long now){
        if(byId(id)==null||reviewer==null||reviewer.isBlank()||reviewer.length()>128||now<0)throw Failure.validation();
        if(revoked(id))return;
        try(var s=query("INSERT INTO control_effect_consent_revocations(tenant_id,consent_id,reviewer,revoked_at) VALUES(?,?,?,?)",
            id,reviewer,now)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}
    }
    private static Failure mismatch(){return Failure.detail(403,ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,List.of());}
    public Reservation reserve(String id,long index,String operation,String route,String mapped,String effect,String request,long epoch,long now){
        var p=byId(id);if(p==null)throw mismatch();valid(id,p.digest(),now);
        var approval=approval(id);
        if(!p.operationId().equals(operation)||approval==null||approval.authorizationEpoch()!=epoch)throw mismatch();
        try{
            long permitted;
            try(var s=query("SELECT permitted_count FROM control_effect_consent_slots WHERE tenant_id=? AND consent_id=? AND slot_index=? AND route_id=? AND mapped_digest=?",id,index,route,mapped);var r=s.executeQuery()){
                if(!r.next())throw mismatch();permitted=r.getLong(1);
            }
            // A slot use exists only for a currently authorized identity in the same operation/route/request.
            try(var s=query("SELECT 1 FROM control_effects e JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation WHERE e.tenant_id=? AND e.effect_id=? AND e.operation_id=? AND e.route_id=? AND e.request_digest=? AND g.state='AUTHORIZED' AND g.expires_at>?",
                    effect,operation,route,request,now);var r=s.executeQuery()){if(!r.next())throw mismatch();}
            try(var s=query("SELECT consent_id,slot_index,request_digest FROM control_effect_consent_uses WHERE tenant_id=? AND effect_id=?",effect);var r=s.executeQuery()){
                if(r.next()){
                    if(!r.getString(1).equals(id)||r.getLong(2)!=index||!r.getString(3).equals(request))throw mismatch();
                    return new Reservation(id,index,effect,request);
                }
            }
            try(var s=query("SELECT COUNT(*) FROM control_effect_consent_uses WHERE tenant_id=? AND consent_id=? AND slot_index=?",id,index);var r=s.executeQuery()){
                if(!r.next())throw Failure.unavailable();
                if(r.getLong(1)>=permitted)throw Failure.detail(409,ControlErrorDetailCode.EFFECT_CONSENT_EXHAUSTED,List.of());
            }
            try(var s=query("INSERT INTO control_effect_consent_uses(tenant_id,consent_id,slot_index,effect_id,request_digest,reserved_at) VALUES(?,?,?,?,?,?)",
                    id,index,effect,request,now)){s.executeUpdate();}
            return new Reservation(id,index,effect,request);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
}
