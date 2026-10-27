// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ManagementPermissionTest {
 @TempDir Path directory;
 private final ContractCodec codec=new ContractCodec();
 private final Ids.TenantId tenant=io.ololabs.toolgate.control.fixtures.Tenants.id("permissions");
 private EnterpriseConditions conditions(){return new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),false,false,null,null);}
 private PostgresStore setup(String... permissions){
  var store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve(UUID.randomUUID()+".sqlite"))),codec);TestSupport.seed(store,codec,tenant,"root");
  store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());
   TestSupport.put(entries,codec,Ids.Kind.USER,new ControlUser("viewer","Viewer",true,1L));GroupGraph.addDefault(entries,codec,Ids.Kind.USER,"viewer");
   TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("viewer-role","Viewer role",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,List.of(new EnterpriseManagementRule(List.of(permissions),EnterpriseGroupType.TEAM,new GroupSelection(List.of(),true),List.of(),conditions(),null))));
   TestSupport.put(entries,codec,Ids.Kind.TEAM,new ControlTeam("viewers","Viewers",true,1L,List.of("viewer"),List.of("viewer-role")));
   tx.save(before,new Directory(before.revision()+1,entries));return null;});return store;
 }
 @Test void tenantAnalyticsReadsOnlyTheVerifiedTenantsTaggedRowsAndRechecksPermissions(){
  var store=setup("audit.read");packagePermissions(store,2,"audit.read");
  var other=io.ololabs.toolgate.control.fixtures.Tenants.id("other");
  TestSupport.seed(store,codec,other,"root");
  long now=1_791_676_800_000L,from=now-3_600_000;
  for(var id:List.of(tenant,other))store.transaction(id,true,tx->{
   long count=id.equals(tenant)?3:91;
   tx.insights().saveCell(new Insights.Cell("TOOL",io.ololabs.toolgate.contracts.v2.InsightsGranularity.HOUR,from,"same-tool","SUCCEEDED"),
    new Insights.CellValue(count,0,0,new long[Insights.BUCKETS]));return null;});
  var insights=new InsightsService(store,InsightsProjector.Settings.defaults(),(cursor,limit)->{throw new AssertionError("Tenant analytics must never enumerate tenants");},store::insightsMode);
  var analytics=new TenantAnalytics(store,codec,insights);
  var actor=new DirectoryService.Actor(tenant,"a".repeat(64),true,false,"viewer");
  var result=analytics.overview(actor,io.ololabs.toolgate.contracts.v2.InsightsMode.BASIC,from,now,now);
  assertEquals(tenant.value(),result.tenantId());
  assertEquals(3,result.series().points().stream().mapToLong(p->p.count()).sum());
  assertEquals(3,result.tools().points().stream().mapToLong(p->p.count()).sum());
  for(var id:List.of(tenant,other))store.transaction(id,true,tx->{
   for(String tool:List.of("same-tool","another-tool")){
    var summary=new Insights.Summary();summary.invocationId="call-"+tool;summary.toolId=tool;summary.action="invoke";summary.state="SUCCEEDED";summary.acceptedAt=from+1;
    tx.insights().saveSummary(summary,true);
   }return null;});
  var drilldown=analytics.overview(actor,io.ololabs.toolgate.contracts.v2.InsightsMode.BASIC,from,now,now,"same-tool",null);
  assertEquals(3,drilldown.series().points().stream().mapToLong(p->p.count()).sum());
  assertEquals(1,drilldown.invocations().items().size());
  assertEquals(tenant.value(),drilldown.invocations().items().getFirst().tenantId());
  assertEquals("same-tool",drilldown.invocations().items().getFirst().toolId());
  var missing=analytics.overview(actor,io.ololabs.toolgate.contracts.v2.InsightsMode.BASIC,from,now,now,"missing-tool",null);
  assertTrue(missing.series().points().isEmpty());assertTrue(missing.invocations().items().isEmpty());
  assertEquals(400,assertThrows(Failure.class,()->analytics.overview(actor,io.ololabs.toolgate.contracts.v2.InsightsMode.BASIC,-1,now,now)).status());
  assertEquals(403,assertThrows(Failure.class,()->analytics.overview(new DirectoryService.Actor(tenant,"b".repeat(64),false,false,"viewer"),io.ololabs.toolgate.contracts.v2.InsightsMode.BASIC,from,now,now)).status());
  packagePermissions(store,3,"user.read");
  assertEquals(403,assertThrows(Failure.class,()->analytics.overview(actor,io.ololabs.toolgate.contracts.v2.InsightsMode.BASIC,from,now,now)).status());
 }
 @Test void resourcePermissionsAreIndependentAndCapabilityResultsUseTheSameVocabulary(){
  var store=setup("user.read");var access=new ManagementAccess(codec);
  store.transaction(tenant,false,tx->{var graph=tx.load();var user=graph.entries().get(Ids.Kind.USER.id("viewer"));
   assertDoesNotThrow(()->access.requireEntry(graph,"viewer","read",user,1));
   assertThrows(Failure.class,()->access.requireEntry(graph,"viewer","update",user,1));
   assertThrows(Failure.class,()->access.requireEntry(graph,"viewer","read",graph.entries().get(Ids.Kind.TEAM.id("viewers")),1));
   assertEquals(Set.of("user.read"),access.capabilities(graph,"viewer",1));return null;});
 }
 @Test void packageImportAndDeploymentUseIndependentLivePermissions() throws Exception {
  var store=setup("package.read");var packages=new ServerPackages(store,codec,null,java.time.Clock.systemUTC());
  var actor=new DirectoryService.Actor(tenant,"b".repeat(64),true,false,"viewer");
  byte[] bytes=ServerPackagesTest.zip(ServerPackagesTest.files());
  assertEquals(403,assertThrows(Failure.class,()->packages.upload(actor,bytes,"denied")).status());
  packagePermissions(store,2,"package.read","package.import");
  var imported=(com.fasterxml.jackson.databind.JsonNode)codec.value(packages.upload(actor,bytes,"import").body());
  assertEquals(200,packages.upload(actor,bytes,"same-import").status());
  String deployment=codec.json(java.util.Map.of("packageDigest",imported.path("packageDigest").asText(),"globals",java.util.Map.of("ItemsApiHost","items.example.com")));
  assertEquals(403,assertThrows(Failure.class,()->packages.deploy(actor,deployment,"denied-deploy")).status());
  packagePermissions(store,3,"package.read","package.deploy");
  assertEquals(200,packages.deploy(actor,deployment,"deploy").status());
  assertEquals(403,assertThrows(Failure.class,()->packages.upload(actor,bytes,"revoked-import")).status());
  boolean enabled=store.transaction(tenant,false,tx->tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get")).enabled());assertFalse(enabled);
 }
 private void packagePermissions(Store store,long revision,String... permissions){
  store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());
   var rules=Arrays.stream(EnterpriseGroupType.values()).map(group->new EnterpriseManagementRule(List.of(permissions),group,new GroupSelection(List.of(),true),List.of(),conditions(),null)).toList();
   TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("viewer-role","Package administrator",true,revision,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));
   tx.save(before,new Directory(before.revision()+1,entries));return null;});
 }
 @Test void superAdminAlwaysHasKnownManagementPermissionsButUnknownPermissionsFailClosed(){
  var store=setup();var access=new ManagementAccess(codec);
  store.transaction(tenant,false,tx->{var graph=tx.load();
   for(var permission:ManagementPermissionCatalog.ALL)assertTrue(access.allowed(graph,"root",permission,Ids.Kind.TEAM,"missing",1),permission);
   assertFalse(access.allowed(graph,"root","invented.permission",Ids.Kind.TEAM,"missing",1));
   assertEquals(ManagementPermissionCatalog.ALL,access.capabilities(graph,"root",1));return null;});
 }
 @Test void legacyActionNamesAndLegacyDeviceScopeAreRejectedAtTheContractBoundary(){
  assertThrows(RuntimeException.class,()->codec.model("{\"actions\":[\"read\"],\"groupType\":\"TEAM\",\"groups\":{\"ids\":[],\"all\":true},\"grantableScopes\":[],\"conditions\":"+codec.json(conditions())+"}",EnterpriseManagementRule.class));
  assertThrows(RuntimeException.class,()->codec.model("{\"actions\":[\"device.approve\"],\"groupType\":\"DEVICE_GROUP\",\"groups\":{\"ids\":[\"devices\"],\"all\":false},\"grantableScopes\":[],\"conditions\":"+codec.json(conditions())+",\"ownDevices\":true}",EnterpriseManagementRule.class));
 }
 @Test void expiredGrantsNeitherAuthorizeRequestsNorAppearAsCapabilities(){
  var store=setup("user.read");store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var expired=new EnterpriseConditions(0L,10L,List.of(),List.of(),List.of(),List.of(),false,false,null,null);
   TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("viewer-role","Expired",true,2L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,List.of(new EnterpriseManagementRule(List.of("user.read"),EnterpriseGroupType.TEAM,new GroupSelection(List.of(),true),List.of(),expired,null))));tx.save(before,new Directory(before.revision()+1,entries));return null;});
  store.transaction(tenant,false,tx->{var access=new ManagementAccess(codec);assertTrue(access.capabilities(tx.load(),"viewer",11).isEmpty());assertFalse(access.allowed(tx.load(),"viewer","user.read",Ids.Kind.TEAM,"viewers",11));return null;});
 }
 @Test void settingsReadAndWritePermissionsAreIndependentAndResponsesOmitUnreadableGroups(){
  var store=setup("server_configuration.read","server_configuration.update");
  store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var rule=codec.model(entries.get(Ids.Kind.ROLE.id("viewer-role")).document(),ControlRole.class).managementRules().getFirst();
   var rules=Arrays.stream(EnterpriseGroupType.values()).map(group->new EnterpriseManagementRule(rule.actions(),group,rule.groups(),rule.grantableScopes(),rule.conditions(),null)).toList();
   TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("viewer-role","Server administrator",true,2L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));tx.save(before,new Directory(before.revision()+1,entries));return null;});
  var actor=new DirectoryService.Actor(tenant,"a".repeat(64),true,false,"viewer");var settings=new ServerSettingsService(store,codec,ClientCheckInPolicy.QUICKSTART);
  var visible=codec.model(settings.get(actor,"server").body(),io.ololabs.toolgate.contracts.v2.ControlSettingsView.class);
  assertNotNull(visible.gateway());assertNull(visible.deviceApproval());assertNull(visible.clientCheckIn());
  assertEquals(403,assertThrows(Failure.class,()->settings.get(actor,"device")).status());
  var changed=codec.model(settings.patch(actor,"{\"gateway\":{\"gatewayName\":\"Scoped server\"}}",1,"server-name","request").body(),io.ololabs.toolgate.contracts.v2.ControlSettingsView.class);
  assertEquals("Scoped server",changed.gateway().gatewayName());assertNull(changed.deviceApproval());
  assertEquals(403,assertThrows(Failure.class,()->settings.patch(actor,"{\"deviceApproval\":{\"autoApprove\":false,\"durationMs\":86400000}}",2,"device-setting","request")).status());
 }

}
