// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.sql.*;
import java.util.*;

/** No independent connection or commit; all records share the invocation transaction. */
final class JdbcNativeFiles implements NativeFileStore {
    private final Connection connection;private final String tenant;private final ContractCodec codec;
    JdbcNativeFiles(Connection connection,String tenant,ContractCodec codec){this.connection=connection;this.tenant=tenant;this.codec=codec;}
    private PreparedStatement query(String sql,Object... values)throws SQLException{
        var s=connection.prepareStatement(sql);s.setString(1,tenant);for(int n=0;n<values.length;n++)s.setObject(n+2,values[n]);return s;
    }
    private void insert(String sql,Object... values){try(var s=query(sql,values)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
    public List<Qualification> qualifications(String device,long now){
        var values=new ArrayList<Qualification>();
        try(var s=query("SELECT qualification_id,document,enabled FROM control_native_file_qualifications WHERE tenant_id=? AND device_id=? AND enabled=true AND expires_at>? ORDER BY qualification_id LIMIT 257",device,now);var r=s.executeQuery()){
            while(r.next())values.add(new Qualification(r.getString(1),codec.model(r.getString(2),NativeFileQualification.class),r.getBoolean(3)));
            if(values.size()>256)throw Failure.conflict();return List.copyOf(values);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public Qualification qualification(String id){
        try(var s=query("SELECT document,enabled FROM control_native_file_qualifications WHERE tenant_id=? AND qualification_id=?",id);var r=s.executeQuery()){
            return r.next()?new Qualification(id,codec.model(r.getString(1),NativeFileQualification.class),r.getBoolean(2)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void qualify(String id,NativeFileQualification v){
        var old=qualification(id);if(old!=null){if(!old.value().equals(v)||!old.enabled())throw Failure.conflict();return;}
        insert("INSERT INTO control_native_file_qualifications(tenant_id,qualification_id,device_id,group_id,image_digest,expires_at,enabled,document) VALUES(?,?,?,?,?,?,true,?)",id,v.deviceId(),v.deviceGroupId(),v.imageDigest(),v.expiresAtUnixMs(),codec.json(v));
    }
    public void revoke(String id){insert("UPDATE control_native_file_qualifications SET enabled=false WHERE tenant_id=? AND qualification_id=? AND enabled=true",id);}
    public Scope scope(String attempt){
        try(var s=query("SELECT operation_id,qualification_id,write_access FROM control_native_file_scopes WHERE tenant_id=? AND attempt_id=?",attempt);var r=s.executeQuery()){
            return r.next()?new Scope(r.getString(1),attempt,r.getString(2),r.getBoolean(3)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void admit(Scope v,String document){insert("INSERT INTO control_native_file_scopes(tenant_id,operation_id,attempt_id,qualification_id,write_access,document) VALUES(?,?,?,?,?,?)",v.operation(),v.attempt(),v.qualification(),v.writeAccess(),document);}
    public Intent intent(String effect){
        try(var s=query("SELECT operation_id,attempt_id,ordinal,intent_digest,document FROM control_native_file_intents WHERE tenant_id=? AND effect_id=?",effect);var r=s.executeQuery()){
            return r.next()?new Intent(effect,r.getString(1),r.getString(2),r.getLong(3),r.getString(4),codec.model(r.getString(5),NativeFileIntent.class)):null;
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void begin(Intent v,long now){
        var old=intent(v.effect());if(old!=null){if(!old.equals(v))throw Failure.conflict();return;}
        insert("INSERT INTO control_effects(tenant_id,effect_id,operation_id,route_id,request_digest,current_generation) VALUES(?,?,?,?,?,1)",v.effect(),v.operation(),"native-files:"+v.value().operation().name(),v.digest());
        insert("INSERT INTO control_native_file_intents(tenant_id,operation_id,attempt_id,effect_id,ordinal,intent_digest,recorded_at,document) VALUES(?,?,?,?,?,?,?,?)",v.operation(),v.attempt(),v.effect(),v.ordinal(),v.digest(),now,codec.json(v.value()));
        insert("INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id) VALUES(?,?,?,?)",v.operation(),v.attempt(),v.effect());
    }
    public List<Intent> intents(String operation){
        var values=new ArrayList<Intent>();
        try(var s=query("SELECT effect_id,attempt_id,ordinal,intent_digest,document FROM control_native_file_intents WHERE tenant_id=? AND operation_id=? ORDER BY effect_id LIMIT 4097",operation);var r=s.executeQuery()){
            while(r.next())values.add(new Intent(r.getString(1),operation,r.getString(2),r.getLong(3),r.getString(4),codec.model(r.getString(5),NativeFileIntent.class)));
            if(values.size()>4096)throw Failure.conflict();return List.copyOf(values);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public List<NativeFileEffectReceipt> receipts(String effect){
        var values=new ArrayList<NativeFileEffectReceipt>();
        try(var s=query("SELECT document FROM control_native_file_receipts WHERE tenant_id=? AND effect_id=? ORDER BY receipt_digest LIMIT 3",effect);var r=s.executeQuery()){
            while(r.next())values.add(codec.model(r.getString(1),NativeFileEffectReceipt.class));return List.copyOf(values);
        }catch(SQLException failure){throw Failure.unavailable();}
    }
    public void receipt(NativeFileEffectReceipt v,String digest,long now){
        if(receipts(v.effectId()).contains(v))return;
        if(receipts(v.effectId()).size()>=2)throw Failure.conflict();
        insert("INSERT INTO control_native_file_receipts(tenant_id,effect_id,receipt_digest,recorded_at,document) VALUES(?,?,?,?,?)",v.effectId(),digest,now,codec.json(v));
    }
    public boolean closed(String attempt){try(var s=query("SELECT 1 FROM control_native_file_closures WHERE tenant_id=? AND attempt_id=?",attempt);var r=s.executeQuery()){return r.next();}catch(SQLException failure){throw Failure.unavailable();}}
    public void close(String attempt,String document,long now){if(!closed(attempt))insert("INSERT INTO control_native_file_closures(tenant_id,attempt_id,recorded_at,document) VALUES(?,?,?,?)",attempt,now,document);}
}
