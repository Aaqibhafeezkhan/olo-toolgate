// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.EndpointDeviceRecord;
import io.ololabs.toolgate.contracts.EndpointState;
import io.ololabs.toolgate.contracts.v2.DeploymentProfile;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.TreeSet;

/** One durable device for colocated profile dependencies; observations never enable or approve it. */
public final class WorkflowDevicePlacement {
    private final Store store; private final Codec codec; private final Clock clock;
    public WorkflowDevicePlacement(Store store, Codec codec, Clock clock) { this.store=store; this.codec=codec; this.clock=clock; }
    public record Selection(String deviceId, String blocker, java.util.List<java.util.List<String>> groupSets) {}
    public Selection resolve(DirectoryService.Actor actor, DeploymentProfile profile) {
        actor.requireAdmin();
        var local=profile.packages().stream().filter(p -> p.deviceGroupIds()!=null && !p.deviceGroupIds().isEmpty()).toList();
        if(local.isEmpty()) return new Selection(null,null,java.util.List.of());
        var required=local.stream().filter(p -> p.required()).toList();
        if(!required.isEmpty()) local=required;
        var groupSets=local.stream().map(p -> p.deviceGroupIds().stream().sorted().toList()).distinct().toList();
        var dependencies=local;
        return store.transaction(actor.tenant(),true,tx -> {
            if(!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId())) throw ManagementAccess.denied();
            TreeSet<String> eligible=null;
            for(var p:dependencies) {
                var members=new TreeSet<String>();
                for(String group:p.deviceGroupIds()) {
                    var entry=tx.load().entries().get(Ids.Kind.DEVICE_GROUP.id(group));
                    if(entry!=null && entry.enabled()) members.addAll(GroupGraph.members(entry,codec));
                }
                if(eligible==null) eligible=members; else eligible.retainAll(members);
            }
            var saved=tx.workflowProfiles().devicePlacement(profile.profileId());
            if(saved!=null) return qualify(tx,saved.deviceId(),eligible,groupSets);
            for(String id:eligible) {
                if(qualify(tx,id,eligible,groupSets).blocker()!=null) continue;
                var selected=tx.workflowProfiles().placeDevice(profile.profileId(),id);
                tx.audit(actor.id(),"WORKFLOW_DEVICE_PLACE","profile:"+profile.profileId(),selected.generation(),
                    "placement-"+DirectoryService.digest(profile.profileId()).substring(0,32),DirectoryService.digest(selected.deviceId()));
                return qualify(tx,selected.deviceId(),eligible,groupSets);
            }
            var groups = new TreeSet<String>(); dependencies.forEach(p -> groups.addAll(p.deviceGroupIds()));
            return new Selection(null,"Waiting for an active device in "+String.join(", ",groups)+" with a recent check-in and approval",groupSets);
        });
    }
    private Selection qualify(Store.Session tx,String id,java.util.Set<String> eligible,java.util.List<java.util.List<String>> groupSets) {
        if(!eligible.contains(id)) return new Selection(id,"Selected device "+id+" is no longer in every requested group set; fenced reassignment is required",groupSets);
        var row=tx.endpoint(id); var entry=tx.load().entries().get(Ids.Kind.DEVICE.id(id));
        if(row==null || entry==null || !entry.enabled()) return new Selection(id,"Selected device "+id+" is not enabled",groupSets);
        var device=codec.model(row.document(),EndpointDeviceRecord.class); long now=clock.millis();
        if(device.state()!=EndpointState.ACTIVE || Boolean.FALSE.equals(device.connectionApproved())
                || device.connectionExpiresAtUnixMs()!=null && device.connectionExpiresAtUnixMs()<=now)
            return new Selection(id,"Selected device "+id+" needs current approval",groupSets);
        if(device.lastSeenUnixMs()<=0 || device.lastSeenUnixMs()>now || now-device.lastSeenUnixMs()>180000)
            return new Selection(id,"Waiting for selected device "+id+" to check in; automatic failover is disabled",groupSets);
        return new Selection(id,null,groupSets);
    }
}
