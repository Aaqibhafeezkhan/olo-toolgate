// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import java.util.*;

/** Places a call on one device (ADR 015 amendment). The request context carries no device, so Control picks from the
 *  execution binding's device group: devices that are enabled, enrolled, approved, online and not switched off by their
 *  owner, narrowed to the caller's
 *  own devices when there are any. Server executors are devices too, so a pool of executor containers is one group and
 *  each call goes to the member with the fewest queued calls; ties go by a hash of the request id, which spreads calls
 *  and keeps a retried request on the same device. */
public final class DevicePlacement {
    /** Queue depth beyond this counts as full; the relay refuses more than 16 pending calls per device. */
    private static final int QUEUE_PROBE=17;
    private final Codec codec;private final ClientCheckInPolicy checkIn;
    public DevicePlacement(Codec codec,ClientCheckInPolicy checkIn){this.codec=codec;this.checkIn=checkIn;}

    /** The one device for this call, or null when the group has no eligible device. */
    public String place(Store.Session tx,RequestContext context,long now){return place(tx,context,now,null);}
    /** As above, preferring devices that report this exact tool installed, so a mixed group does not place a call on a
     *  member that cannot run it. When none report it, the relay's own installation check decides. */
    public String place(Store.Session tx,RequestContext context,long now,AuthorizationRequest request,String toolDigest,String packageDigest){
        var deployment=deployment(tx,packageDigest);
        return place(tx,context,now,id->{var saved=tx.endpointConfiguration(id);if(saved==null||saved.localTools()==null)return false;
            // A Tool Host still reporting a tool its deployment moved elsewhere no longer gets its calls.
            if(deployment!=null&&tx.packages().toolHost(id)!=null&&!ServerPackages.targets(tx.load(),codec,deployment,id))return false;
            return codec.model(saved.localTools(),LocalToolCatalog.class).tools().stream().anyMatch(t->t.enabled()&&t.toolId().equals(request.toolId())&&t.action().equals(request.action())&&t.toolDigest().equals(toolDigest)&&t.packageDigest().equals(packageDigest));});
    }
    /** The server package deployment running this package, when the tool is a server tool. */
    private com.fasterxml.jackson.databind.JsonNode deployment(Store.Session tx,String packageDigest){
        if(packageDigest==null)return null;
        for(var raw:tx.packages().deployments()){var d=(com.fasterxml.jackson.databind.JsonNode)codec.value(raw);if(d.path("packageDigest").asText().equals("sha256:"+packageDigest))return d;}
        return null;
    }
    /** Every device a call for this context could be placed on, before load balancing. A catalog lists their tools. */
    public List<String> candidates(Store.Session tx,RequestContext context,long now){return candidates(tx,context,now,null);}
    private String place(Store.Session tx,RequestContext context,long now,java.util.function.Predicate<String> runs){
        var candidates=candidates(tx,context,now,runs);
        if(candidates.size()<2)return candidates.isEmpty()?null:candidates.getFirst();
        var queued=new HashMap<String,Integer>();for(var id:candidates)queued.put(id,tx.mcp().pending(id,QUEUE_PROBE).size());
        int least=Collections.min(queued.values());var lightest=candidates.stream().filter(id->queued.get(id)==least).toList();
        return lightest.get(Math.floorMod(DirectoryService.digest(context.requestId()).hashCode(),lightest.size()));
    }
    private List<String> candidates(Store.Session tx,RequestContext context,long now,java.util.function.Predicate<String> runs){
        var directory=tx.load();
        var bindingEntry=context.bindingId()==null?null:directory.entries().get(Kind.BINDING.id(context.bindingId()));
        if(bindingEntry==null||!bindingEntry.enabled())return List.of();
        var binding=codec.model(bindingEntry.document(),ControlExecutionBinding.class);
        var groupEntry=directory.entries().get(Kind.DEVICE_GROUP.id(binding.deviceGroupId()));
        if(groupEntry==null||!groupEntry.enabled())return List.of();
        var eligible=new TreeMap<String,ControlDevice>();
        for(var id:codec.model(groupEntry.document(),ControlDeviceGroup.class).deviceIds()){
            var entry=directory.entries().get(Kind.DEVICE.id(id));if(entry==null||!entry.enabled()||!online(tx,id,now))continue;
            eligible.put(id,codec.model(entry.document(),ControlDevice.class));
        }
        if(runs!=null){var running=new TreeMap<String,ControlDevice>();eligible.forEach((id,d)->{if(runs.test(id))running.put(id,d);});if(!running.isEmpty())eligible=running;}
        var own=context.userId()==null?List.<String>of():eligible.values().stream().filter(d->context.userId().equals(d.ownerUserId())).map(ControlDevice::id).toList();
        return own.isEmpty()?List.copyOf(eligible.keySet()):own;
    }
    /** A device chosen by a person must belong to the binding's device group. */
    public boolean member(Store.Session tx,String bindingId,String deviceId){
        var directory=tx.load();var bindingEntry=bindingId==null?null:directory.entries().get(Kind.BINDING.id(bindingId));if(bindingEntry==null)return false;
        var groupEntry=directory.entries().get(Kind.DEVICE_GROUP.id(codec.model(bindingEntry.document(),ControlExecutionBinding.class).deviceGroupId()));
        return groupEntry!=null&&codec.model(groupEntry.document(),ControlDeviceGroup.class).deviceIds().contains(deviceId);
    }
    private boolean online(Store.Session tx,String deviceId,long now){
        var row=tx.endpoint(deviceId);if(row==null)return false;var d=codec.model(row.document(),EndpointDeviceRecord.class);
        return d.state()==EndpointState.ACTIVE&&!Boolean.FALSE.equals(d.connectionApproved())&&!poweredOff(d)
            &&(d.connectionExpiresAtUnixMs()==null||d.connectionExpiresAtUnixMs()>now)&&!checkIn.offline(d.lastSeenUnixMs(),now);
    }
    /** The owner switched the device off in its client; it gets no calls at once, before the offline timeout. */
    public static boolean poweredOff(EndpointDeviceRecord device){return device!=null&&poweredOff(device.report());}
    /** Reports from clients without the switch carry no power and count as on. */
    public static boolean poweredOff(ClientReport report){return report!=null&&report.power()==DevicePower.OFF;}
}
