// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.sql.*;
import java.util.*;

/** Shared PostgreSQL/SQLite journal; caller owns the serialized tenant transaction. */
final class JdbcKillStore implements KillStore {
    private final Connection connection;private final String tenant;private final Codec codec;
    JdbcKillStore(Connection connection,String tenant,Codec codec){this.connection=connection;this.tenant=tenant;this.codec=codec;}
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var statement=connection.prepareStatement(sql);statement.setString(1,tenant);
        for(int n=0;n<values.length;n++)statement.setObject(n+2,values[n]);return statement;
    }
    private Event row(ResultSet r)throws SQLException{return new Event(r.getString("event_id"),r.getString("issuer"),r.getLong("issuer_counter"),
        r.getString("event_kind"),r.getString("scope_kind"),r.getString("scope_value"),r.getString("mode"),
        r.getLong("authorization_epoch"),r.getLong("issued_at"),r.getString("document"));}
    private Proposal proposalRow(ResultSet r)throws SQLException{return new Proposal(r.getString("proposal_id"),r.getString("requester_id"),r.getString("kind"),
        r.getLong("authorization_epoch"),r.getString("request_digest"),r.getString("document"),r.getLong("created_at"),r.getLong("expires_at"));}
    public void registerGateway(String id,long at){
        if(!id.matches("[a-f0-9]{64}")||at<0)throw Failure.validation();
        try(var s=query("INSERT INTO control_kill_gateways(tenant_id,gateway_id,registered_at) VALUES(?,?,?) ON CONFLICT(tenant_id,gateway_id) DO NOTHING",id,at)){s.executeUpdate();}
        catch(SQLException unavailable){throw Failure.unavailable();}
    }
    public boolean registeredGateway(String id){
        try(var s=query("SELECT 1 FROM control_kill_gateways WHERE tenant_id=? AND gateway_id=?",id);var r=s.executeQuery()){return r.next();}
        catch(SQLException unavailable){throw Failure.unavailable();}
    }
    public List<Component> components(){
        try(var s=query("SELECT component_kind,component_id FROM (SELECT 'TOOL_HOST' AS component_kind,device_id AS component_id FROM control_tool_hosts WHERE tenant_id=? "+
                "UNION ALL SELECT 'ENDPOINT',device_id FROM control_endpoints e WHERE tenant_id=? AND NOT EXISTS(SELECT 1 FROM control_tool_hosts h WHERE h.tenant_id=e.tenant_id AND h.device_id=e.device_id) "+
                "UNION ALL SELECT 'GATEWAY',gateway_id FROM control_kill_gateways WHERE tenant_id=?) c ORDER BY component_kind,component_id LIMIT 10001",tenant,tenant);var r=s.executeQuery()){
            var result=new ArrayList<Component>();while(r.next())result.add(new Component(r.getString(1),r.getString(2)));
            if(result.size()>10000)throw Failure.unavailable();return List.copyOf(result);
        }catch(SQLException unavailable){throw Failure.unavailable();}
    }
    private long count(boolean archived){
        try(var s=query(archived?"SELECT count(*) FROM control_kill_archive WHERE tenant_id=?":"SELECT count(*) FROM control_kill_events WHERE tenant_id=?");var r=s.executeQuery()){if(!r.next())throw Failure.unavailable();return r.getLong(1);}
        catch(SQLException unavailable){throw Failure.unavailable();}
    }
    public long retainedCount(){return count(false);}
    public long archivedCount(){return count(true);}
    public EmergencyRequest emergency(String id){
        try(var s=query("SELECT request_id,request_digest,event_id,certificate_sha256,not_after,applied_at FROM control_kill_breakglass_requests WHERE tenant_id=? AND request_id=?",id);var r=s.executeQuery()){
            return r.next()?new EmergencyRequest(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getLong(6)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void recordEmergency(EmergencyRequest value){
        io.ololabs.toolgate.control.domain.Ids.valid(value.id());
        if(!value.digest().matches("[a-f0-9]{64}")||!value.certificate().matches("[a-f0-9]{64}"))throw Failure.validation();
        try(var s=query("INSERT INTO control_kill_breakglass_requests(tenant_id,request_id,request_digest,event_id,certificate_sha256,not_after,applied_at) VALUES(?,?,?,?,?,?,?)",
            value.id(),value.digest(),value.event(),value.certificate(),value.notAfter(),value.at())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public Proposal proposal(String id){
        try(var s=query("SELECT * FROM control_kill_proposals WHERE tenant_id=? AND proposal_id=?",id);var r=s.executeQuery()){return r.next()?proposalRow(r):null;}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<Proposal> proposals(String after,int limit){
        if(limit<1||limit>100)throw Failure.validation();
        try(var s=query("SELECT * FROM control_kill_proposals WHERE tenant_id=? AND proposal_id>? ORDER BY proposal_id LIMIT ?",after,limit);var r=s.executeQuery()){
            var result=new ArrayList<Proposal>();while(r.next())result.add(proposalRow(r));return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void propose(Proposal value){
        if(!Set.of("KILL","RECOVERY").contains(value.kind())||!DirectoryService.digest(value.document()).equals(value.digest()))throw Failure.validation();
        try(var s=query("INSERT INTO control_kill_proposals(tenant_id,proposal_id,requester_id,kind,authorization_epoch,request_digest,document,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
            value.id(),value.requester(),value.kind(),value.epoch(),value.digest(),value.document(),value.createdAt(),value.expiresAt())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<KillReview> reviews(String id){
        try(var s=query("SELECT reviewer_id,approved,reviewed_at FROM control_kill_reviews WHERE tenant_id=? AND proposal_id=? ORDER BY reviewed_at,reviewer_id",id);var r=s.executeQuery()){
            var result=new ArrayList<KillReview>();while(r.next())result.add(new KillReview(r.getString(1),r.getBoolean(2),r.getLong(3)));return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void review(String id,KillReview value){
        try(var s=query("INSERT INTO control_kill_reviews(tenant_id,proposal_id,reviewer_id,approved,reviewed_at) VALUES(?,?,?,?,?)",id,value.reviewerUserId(),value.approved(),value.reviewedAtUnixMs())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public Closure closure(String id){
        try(var s=query("SELECT state,event_id,closed_at FROM control_kill_closures WHERE tenant_id=? AND proposal_id=?",id);var r=s.executeQuery()){
            return r.next()?new Closure(r.getString(1),r.getString(2),r.getLong(3)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void close(String id,Closure value){
        try(var s=query("INSERT INTO control_kill_closures(tenant_id,proposal_id,state,event_id,closed_at) VALUES(?,?,?,?,?)",id,value.state(),value.event(),value.at())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public Scope scope(String attempt){
        try(var s=query("SELECT operation_id,attempt_id,device_id,package_id,package_digest,publisher_id,tool_id,pool_id,image_digest FROM control_invocation_kill_scopes WHERE tenant_id=? AND attempt_id=?",attempt);var r=s.executeQuery()){
            return r.next()?new Scope(r.getString(1),r.getString(2),r.getString(3),new KillPolicy.Subject(r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9))):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void bindScope(Scope value){
        var previous=scope(value.attempt());if(previous!=null){if(!previous.equals(value))throw Failure.conflict();return;}
        var subject=value.subject();
        try(var s=query("INSERT INTO control_invocation_kill_scopes(tenant_id,operation_id,attempt_id,device_id,package_id,package_digest,publisher_id,tool_id,pool_id,image_digest) VALUES(?,?,?,?,?,?,?,?,?,?)",
            value.operation(),value.attempt(),value.device(),subject.packageId(),subject.packageDigest(),subject.publisher(),subject.toolId(),subject.poolId(),subject.imageDigest())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public long nextCounter(String issuer){
        if(!Set.of("control","control-breakglass").contains(issuer))throw Failure.validation();
        try(var s=query("SELECT COALESCE(MAX(issuer_counter),0)+1 FROM control_kill_events WHERE tenant_id=? AND issuer=?",issuer);var r=s.executeQuery()){
            if(!r.next())throw Failure.unavailable();long value=r.getLong(1);
            if(value<1||value>9999999999999999L)throw Failure.conflict();return value;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void append(Event value){
        String eventTenant;long epoch,issued;String id,issuer,scope=null,scopeValue=null,mode=null;
        if(value.kind().equals("KILL")){
            var model=codec.model(value.document(),KillEvent.class);eventTenant=model.tenantId();epoch=model.authorizationEpoch();
            issued=model.issuedAtUnixMs();id=model.eventId();
            // Generated issuer enum uses wire values through toString only in serialization; compare canonical document.
            issuer=((com.fasterxml.jackson.databind.JsonNode)codec.value(value.document())).path("issuer").asText();
            scope=model.scope().kind().name();scopeValue=model.scope().value();mode=model.mode().name();
        }else if(value.kind().equals("RECOVERY")){
            var model=codec.model(value.document(),RecoveryEvent.class);eventTenant=model.tenantId();epoch=model.authorizationEpoch();
            issued=model.issuedAtUnixMs();id=model.eventId();issuer=model.issuer();
        }else throw Failure.validation();
        if(!tenant.equals(eventTenant)||epoch!=value.epoch()||issued!=value.issuedAt()||!id.equals(value.id())
            ||!issuer.equals(value.issuer())||!id.equals(issuer+":"+value.counter())||!Objects.equals(scope,value.scope())
            ||!Objects.equals(scopeValue,value.value())||!Objects.equals(mode,value.mode()))throw Failure.validation();
        try(var s=query("INSERT INTO control_kill_events(tenant_id,event_id,issuer,issuer_counter,event_kind,scope_kind,scope_value,mode,authorization_epoch,issued_at,document) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id,issuer,value.counter(),value.kind(),scope,scopeValue,mode,epoch,issued,value.document())){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public Event byId(String id){
        try(var s=query("SELECT * FROM control_kill_events WHERE tenant_id=? AND event_id=?",id);var r=s.executeQuery()){return r.next()?row(r):null;}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    private List<Event> events(boolean effective){
        String filter=effective?" AND e.event_kind='KILL' AND NOT EXISTS(SELECT 1 FROM control_kill_supersessions s WHERE s.tenant_id=e.tenant_id AND s.kill_event_id=e.event_id)":
            " AND NOT EXISTS(SELECT 1 FROM control_kill_archive a WHERE a.tenant_id=e.tenant_id AND a.event_id=e.event_id)";
        try(var s=query("SELECT e.* FROM control_kill_events e WHERE e.tenant_id=?"+filter+" ORDER BY e.authorization_epoch LIMIT 2049");var r=s.executeQuery()){
            var result=new ArrayList<Event>();while(r.next())result.add(row(r));if(result.size()>2048)throw Failure.unavailable();return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<Event> visible(){return events(false);}
    public List<Event> effective(){return events(true);}
    public void supersede(String kill,String recovery){
        try(var s=query("INSERT INTO control_kill_supersessions(tenant_id,kill_event_id,recovery_event_id) VALUES(?,?,?)",kill,recovery)){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public void archive(String id,long at){
        try(var s=query("INSERT INTO control_kill_archive(tenant_id,event_id,archived_at) VALUES(?,?,?) ON CONFLICT(tenant_id,event_id) DO NOTHING",id,at)){s.executeUpdate();}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public void acknowledge(Acknowledgement v){
        if(!Set.of("TOOL_HOST","ENDPOINT","GATEWAY").contains(v.kind())||v.component()==null||v.component().isBlank()
            ||v.component().length()>256||v.epoch()<0||v.at()<0||!Set.of("PUSH","POLL","CHECK_IN").contains(v.path()))throw Failure.validation();
        try(var s=query("INSERT INTO control_kill_acks(tenant_id,component_kind,component_id,acked_epoch,acked_at,delivery_path) VALUES(?,?,?,?,?,?) "+
            "ON CONFLICT(tenant_id,component_kind,component_id) DO UPDATE SET acked_epoch=excluded.acked_epoch,acked_at=excluded.acked_at,delivery_path=excluded.delivery_path "+
            "WHERE excluded.acked_epoch>=control_kill_acks.acked_epoch AND excluded.acked_at>=control_kill_acks.acked_at",v.kind(),v.component(),v.epoch(),v.at(),v.path())){
            if(s.executeUpdate()!=1)throw Failure.conflict();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<Acknowledgement> acknowledgements(){
        try(var s=query("SELECT component_kind,component_id,acked_epoch,acked_at,delivery_path FROM control_kill_acks WHERE tenant_id=? ORDER BY component_kind,component_id LIMIT 10001");var r=s.executeQuery()){
            var result=new ArrayList<Acknowledgement>();while(r.next())result.add(new Acknowledgement(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),r.getString(5)));
            if(result.size()>10000)throw Failure.unavailable();return List.copyOf(result);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public boolean killOnlySince(long previous,long current){
        if(previous<0||current<previous)return false;if(previous==current)return true;
        try(var s=query("SELECT COUNT(*) FROM control_kill_events WHERE tenant_id=? AND authorization_epoch>? AND authorization_epoch<=?",previous,current);var r=s.executeQuery()){
            return r.next()&&r.getLong(1)==current-previous;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
}
