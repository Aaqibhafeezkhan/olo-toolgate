// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.sql.*;
import java.util.*;

/** No independent transaction: events commit with source state; leases fence acknowledgements. */
final class JdbcApplicationEventStore implements ApplicationEventStore {
    private final Connection connection; private final String tenant;
    JdbcApplicationEventStore(Connection connection, String tenant) { this.connection = connection; this.tenant = tenant; }
    private PreparedStatement query(String sql, Object... values) throws SQLException {
        var s = connection.prepareStatement(sql); s.setString(1, tenant);
        for (int i = 0; i < values.length; i++) s.setObject(i+2, values[i]);
        return s;
    }
    public void append(ApplicationEventSource source, String id, String key, ApplicationEventKind kind, long now, String data) {
        try {
            try (var s=query("DELETE FROM control_application_events WHERE tenant_id=? AND source_type=? AND source_id=? AND sequence<=(SELECT COALESCE(MAX(sequence),0)-4095 FROM control_application_events WHERE tenant_id=? AND source_type=? AND source_id=?)",source.name(),id,tenant,source.name(),id)){s.executeUpdate();}
            try (var s = query("""
                INSERT INTO control_application_events(tenant_id,source_type,source_id,sequence,event_key,event_id,event_kind,created_at,document)
                SELECT ?,?,?,COALESCE(MAX(sequence),0)+1,?,?,?,?,? FROM control_application_events
                WHERE tenant_id=? AND source_type=? AND source_id=? ON CONFLICT DO NOTHING
                """, source.name(), id, key, "evt-"+UUID.randomUUID(), kind.name(), now, data, tenant, source.name(), id)) { s.executeUpdate(); }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public List<ApplicationEvent> page(ApplicationEventSource source, String id, long after, int limit) {
        if (after<0 || limit<1 || limit>200) throw Failure.validation();
        try (var s=query("SELECT event_id,sequence,event_kind,created_at,document FROM control_application_events WHERE tenant_id=? AND source_type=? AND source_id=? AND sequence>? ORDER BY sequence LIMIT ?",source.name(),id,after,limit);var r=s.executeQuery()) {
            var result=new ArrayList<ApplicationEvent>();
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            while(r.next()) {
                if(result.isEmpty() && after>0 && r.getLong(2)>after+1)throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.CONFLICT,409,"Event cursor expired; register a new observation from retained history");
                var data=json.readValue(r.getString(5),new com.fasterxml.jackson.core.type.TypeReference<Map<String,com.fasterxml.jackson.databind.JsonNode>>(){});
                result.add(new ApplicationEvent(r.getString(1),tenant,source,id,r.getLong(2),ApplicationEventKind.valueOf(r.getString(3)),r.getLong(4),data));
            }
            return List.copyOf(result);
        } catch (SQLException | java.io.IOException failure) { throw Failure.unavailable(); }
    }
    private Subscription row(ResultSet r)throws SQLException {
        return new Subscription(r.getString("subscription_id"),r.getString("user_id"),r.getString("principal_id"),r.getString("identity_digest"),
            r.getString("source_type"),r.getString("source_id"),r.getString("source_key"),r.getString("event_kinds"),r.getString("endpoint"),r.getLong("expires_at"),
            r.getLong("key_version"),r.getString("secret"),r.getString("state"),r.getLong("cursor"),r.getInt("attempts"),r.getLong("next_attempt"),r.getString("lease_id"),r.getLong("lease_until"),r.getString("request_digest"));
    }
    public Subscription get(String id) {
        try(var s=query("SELECT * FROM control_application_subscriptions WHERE tenant_id=? AND subscription_id=?",id);var r=s.executeQuery()){return r.next()?row(r):null;}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    private List<Subscription> list(String sql,Object...values) {
        try(var s=query(sql,values);var r=s.executeQuery()){var result=new ArrayList<Subscription>();while(r.next())result.add(row(r));return List.copyOf(result);}
        catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<Subscription> owned(String user,String after,int limit){return list("SELECT * FROM control_application_subscriptions WHERE tenant_id=? AND user_id=? AND subscription_id>? ORDER BY subscription_id LIMIT ?",user,after,limit);}
    public void insert(Subscription sub) {
        try(var count=query("SELECT count(*) FROM control_application_subscriptions WHERE tenant_id=?");var r=count.executeQuery()) {
            r.next();if(r.getInt(1)>=1000)throw Failure.unavailable();
            try(var owned=query("SELECT count(*) FROM control_application_subscriptions WHERE tenant_id=? AND user_id=?",sub.userId());var users=owned.executeQuery()){
                users.next();if(users.getInt(1)>=100)throw Failure.unavailable();
            }
            try(var s=query("""
                INSERT INTO control_application_subscriptions(tenant_id,subscription_id,user_id,principal_id,identity_digest,source_type,source_id,source_key,event_kinds,endpoint,expires_at,key_version,secret,state,cursor,attempts,next_attempt,lease_until,request_digest)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,0,?)
                """,sub.id(),sub.userId(),sub.principalId(),sub.identityDigest(),sub.sourceType(),sub.sourceId(),sub.sourceKey(),sub.eventKinds(),sub.endpoint(),sub.expiresAt(),sub.keyVersion(),sub.secret(),sub.state(),sub.cursor(),sub.requestDigest())){s.executeUpdate();}
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void prune(long before){update("DELETE FROM control_application_subscriptions WHERE tenant_id=? AND expires_at<? AND lease_until<?",before,before);}
    private int update(String sql,Object...values){try(var s=query(sql,values)){return s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public void state(String id,String state){
        try(var s=connection.prepareStatement("UPDATE control_application_subscriptions SET state=?,lease_id=NULL,lease_until=0 WHERE tenant_id=? AND subscription_id=?")){
            s.setString(1,checked(state));s.setString(2,tenant);s.setString(3,id);s.executeUpdate();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    private static String checked(String state){if(!Set.of("PENDING","ACTIVE","REVOKED","FAILED").contains(state))throw Failure.validation();return state;}
    public List<Subscription> due(long now,int limit){return list("SELECT * FROM control_application_subscriptions WHERE tenant_id=? AND state='ACTIVE' AND expires_at>? AND next_attempt<=? AND lease_until<=? ORDER BY next_attempt,subscription_id LIMIT ?",now,now,now,limit);}
    public boolean lease(String id,String lease,long now) {
        try(var s=connection.prepareStatement("UPDATE control_application_subscriptions SET lease_id=?,lease_until=? WHERE tenant_id=? AND subscription_id=? AND state='ACTIVE' AND expires_at>? AND lease_until<=?")) {
            s.setString(1,lease);s.setLong(2,now+30000);s.setString(3,tenant);s.setString(4,id);s.setLong(5,now);s.setLong(6,now);return s.executeUpdate()==1;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void acknowledge(String id,String lease,long cursor,long next) {
        try(var s=connection.prepareStatement("UPDATE control_application_subscriptions SET cursor=?,attempts=0,next_attempt=?,lease_id=NULL,lease_until=0 WHERE tenant_id=? AND subscription_id=? AND lease_id=? AND state='ACTIVE'")) {
            s.setLong(1,cursor);s.setLong(2,next);s.setString(3,tenant);s.setString(4,id);s.setString(5,lease);s.executeUpdate();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void retry(String id,String lease,int attempts,long next,String state) {
        try(var s=connection.prepareStatement("UPDATE control_application_subscriptions SET attempts=?,next_attempt=?,state=?,lease_id=NULL,lease_until=0 WHERE tenant_id=? AND subscription_id=? AND lease_id=? AND state='ACTIVE'")) {
            s.setInt(1,attempts);s.setLong(2,next);s.setString(3,checked(state));s.setString(4,tenant);s.setString(5,id);s.setString(6,lease);s.executeUpdate();
        }catch(SQLException failure){throw Failure.unavailable();}
    }
}
