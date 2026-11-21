// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import java.sql.*;
import java.util.*;

/** No independent connection or commit: trigger state is atomic with directory audit and idempotency (PostgreSQL and SQLite). */
final class PostgresTriggerStore implements TriggerStore {
    static final int MAX_PAGE = 513;
    private final Connection connection;
    private final String tenant;
    PostgresTriggerStore(Connection connection,String tenant){this.connection=connection;this.tenant=tenant;}
    private PreparedStatement statement(String sql,Kind kind)throws SQLException{var s=connection.prepareStatement(sql);s.setString(1,tenant);s.setString(2,kind.name());return s;}
    public Row get(Kind kind,String id){try(var s=statement("SELECT record_id,revision,document FROM control_triggers WHERE tenant_id=? AND kind=? AND record_id=?",kind)){s.setString(3,id);try(var r=s.executeQuery()){return r.next()?new Row(r.getString(1),r.getLong(2),r.getString(3)):null;}}catch(SQLException failure){throw Failure.unavailable();}}
    public List<Row> page(Kind kind,String prefix,String after,int limit){
        if(limit<1||limit>MAX_PAGE||prefix==null||after==null)throw Failure.validation();
        // A prefix comparison, not LIKE: ids carry no wildcard semantics. "" sorts before every id.
        try(var s=statement("SELECT record_id,revision,document FROM control_triggers WHERE tenant_id=? AND kind=? AND record_id>? AND substr(record_id,1,?)=? ORDER BY record_id LIMIT ?",kind)){
            s.setString(3,after);s.setInt(4,prefix.length());s.setString(5,prefix);s.setInt(6,limit);
            try(var r=s.executeQuery()){var rows=new ArrayList<Row>();while(r.next())rows.add(new Row(r.getString(1),r.getLong(2),r.getString(3)));return List.copyOf(rows);}
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public long count(Kind kind){try(var s=statement("SELECT count(*) FROM control_triggers WHERE tenant_id=? AND kind=?",kind);var r=s.executeQuery()){r.next();return r.getLong(1);}catch(SQLException failure){throw Failure.unavailable();}}
    public void save(Kind kind,Row row,long expected){try{
        var previous=(kind==Kind.ASSIGNMENT||kind==Kind.INSTANCE)?get(kind,row.id()):null;
        if(expected==0){try(var s=statement("INSERT INTO control_triggers(tenant_id,kind,record_id,revision,document) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",kind)){s.setString(3,row.id());s.setLong(4,row.revision());s.setString(5,row.document());if(s.executeUpdate()!=1)throw Failure.conflict();}}
        else{if(row.revision()!=expected+1)throw Failure.conflict();try(var s=connection.prepareStatement("UPDATE control_triggers SET revision=?,document=? WHERE tenant_id=? AND kind=? AND record_id=? AND revision=?")){s.setLong(1,row.revision());s.setString(2,row.document());s.setString(3,tenant);s.setString(4,kind.name());s.setString(5,row.id());s.setLong(6,expected);if(s.executeUpdate()!=1)throw Failure.conflict();}}
        notification(kind,row,previous);
    }catch(SQLException failure){throw Failure.unavailable();}}
    private void notification(Kind kind,Row row,Row previous) {
        if(kind!=Kind.ASSIGNMENT&&kind!=Kind.INSTANCE)return;
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var document=json.readTree(row.document());
            var projected=projection(json,kind,row.id(),document);
            if(projected==null)return;
            if(previous!=null&&projected.equals(projection(json,kind,row.id(),json.readTree(previous.document()))))return;
            String source=kind==Kind.ASSIGNMENT?document.path("assignment").path("metadata").path("uid").asText():row.id().split("/",2)[0];
            if(!source.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))return;
            new JdbcApplicationEventStore(connection,tenant).append(io.ololabs.toolgate.contracts.v2.ApplicationEventSource.TRIGGER,
                source,DirectoryService.digest(kind+":"+row.id()+":"+row.revision()),io.ololabs.toolgate.contracts.v2.ApplicationEventKind.TRIGGER_STATUS,
                System.currentTimeMillis(),json.writeValueAsString(projected));
        }catch(java.io.IOException invalid){throw Failure.unavailable();}
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode projection(com.fasterxml.jackson.databind.ObjectMapper json,Kind kind,String id,com.fasterxml.jackson.databind.JsonNode document) {
        var out=json.createObjectNode();
        if(kind==Kind.ASSIGNMENT) {
            if(!document.path("assignment").isObject())return null;
            out.put("phase",document.path("removed").asBoolean()?"Removed":"Configured");
            out.put("generation",document.path("assignment").path("metadata").path("generation").asLong());
        } else {
            String phase=document.path("view").path("phase").asText();
            if(phase.isEmpty()||phase.length()>32)return null;
            out.put("instanceId",id);out.put("phase",phase);
        }
        return out;
    }
    public boolean delete(Kind kind,String id,long expected){
        if(!PURGEABLE.contains(kind))throw Failure.validation();
        try(var s=statement("DELETE FROM control_triggers WHERE tenant_id=? AND kind=? AND record_id=? AND revision=?",kind)){s.setString(3,id);s.setLong(4,expected);return s.executeUpdate()==1;}
        catch(SQLException failure){throw Failure.unavailable();}
    }
}
