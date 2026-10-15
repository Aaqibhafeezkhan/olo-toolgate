// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;
import io.ololabs.toolgate.contracts.*;
import java.util.*;
/** Device inventory and epoch adoption never supply runtime authorization. */
public final class EndpointAdoptions {
 private final Codec codec;public EndpointAdoptions(Codec codec){this.codec=codec;}
 public static String digest(Codec codec,io.ololabs.toolgate.control.domain.Ids.TenantId tenant,io.ololabs.toolgate.control.domain.Directory directory){
  var snapshot=(com.fasterxml.jackson.databind.JsonNode)codec.value(codec.snapshot(tenant,directory,false));for(var identity:snapshot.path("identityBindings")){var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)identity;metadata.remove(java.util.List.of("lastAttemptUnixMs","attemptCount","revision"));}return DirectoryService.digest(codec.json(snapshot));
 }
 public EndpointAdoption poll(Store.Session tx,EndpointDeviceRecord device,String server,String acknowledged,List<BuiltinToolInfo> localTools){
  var directory=tx.load();var saved=tx.endpointConfiguration(device.deviceId());var digest=tx.adoptionDigest(new io.ololabs.toolgate.control.domain.Ids.TenantId(device.tenantId()),codec);
  var adoption=new EndpointAdoption(server,device.deviceId(),directory.revision(),tx.enterprise().authorizationEpoch(),digest);var received=digest.equals(acknowledged)?digest:null;
  var catalog=localTools==null?(saved==null?codec.json(new LocalToolCatalog(List.of())):saved.localTools()):codec.json(new LocalToolCatalog(localTools));if(catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>49152)throw Failure.validation();
  tx.saveEndpointConfiguration(new Store.EndpointConfiguration(device.deviceId(),directory.revision(),codec.json(adoption),received,catalog));if(received!=null)tx.enterprise().acknowledge(new EnterpriseAdoptionStatus(device.deviceId(),directory.revision(),tx.enterprise().authorizationEpoch(),digest,System.currentTimeMillis()));return received==null?adoption:null;
 }
}
