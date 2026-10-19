// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.CredentialKind;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class EnterpriseVaultTest {
 @TempDir java.nio.file.Path temp;
 EnterpriseConformanceTest graph;PostgresStore store;DirectoryService.Actor admin;VaultService vault;
 @BeforeEach void setup(){graph=new EnterpriseConformanceTest();graph.graph();var all=new GroupSelection(List.of(),true);var ceiling=new EnterpriseScope(all,all,List.of(),true,Arrays.stream(ResourceKind.values()).map(k->new EnterpriseResourceRule(k,"",EnterpriseResourceMatch.ANY)).toList(),graph.conditions());var rules=Arrays.stream(EnterpriseGroupType.values()).map(k->new EnterpriseManagementRule(List.of("secret.read","administration.recover"),k,all,List.of(ceiling),graph.conditions(),null)).toList();graph.add(Ids.Kind.ROLE,new ControlRole("test-recovery-role","Scoped administrator",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));graph.add(Ids.Kind.TEAM,new ControlTeam("test-recovery-team","Reviewed administrators",true,1L,List.of("admin"),List.of("test-recovery-role")));store=new PostgresStore(SqliteState.open(temp.resolve("vault.sqlite")),graph.codec);store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),graph.directory());return null;});admin=new DirectoryService.Actor(EnterpriseConformanceTest.TENANT,"a".repeat(64),true,false,"admin");vault=new VaultService(store,graph.codec,new byte[32]);}
 void put(Map<Ids.RecordId,Directory.Entry> entries,ControlRole role){var entry=graph.codec.entry(Ids.Kind.ROLE,graph.codec.json(role));entries.put(entry.id(),entry);}
 void authorize(){store.transaction(admin.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var role=graph.codec.model(entries.get(Ids.Kind.ROLE.id("test-recovery-role")).document(),ControlRole.class);var rules=role.managementRules().stream().map(r->{var actions=new ArrayList<>(r.actions());actions.addAll(List.of("secret.read_value","secret.write"));return new EnterpriseManagementRule(actions,r.groupType(),r.groups(),r.grantableScopes(),r.conditions(),null);}).toList();put(entries,new ControlRole(role.id(),role.name(),true,role.revision()+1,role.portalRole(),role.roleType(),rules));tx.save(before,new Directory(before.revision()+1,entries));return null;});}
 String write(String value){return graph.codec.json(new EnterpriseVaultWrite("provider/token",value,"tools-a","devices-a"));}
 @Test void portalAdministratorNeedsExplicitSecretPurposeManagement(){assertEquals(403,assertThrows(Failure.class,()->vault.write(admin,write("secret"),"key")).status());assertEquals(List.of(),graph.codec.model(vault.list(admin).body(),EnterpriseVaultPage.class).items());}
 @Test void exactRetryNeverChangesCiphertextAndConflictingSecretIsRejected(){authorize();var response=vault.write(admin,write("private-custody-value"),"key");assertEquals(201,response.status());String first=store.transaction(admin.tenant(),false,tx->tx.enterprise().secret("provider/token").cipher());assertFalse(first.contains("private-custody-value"));assertEquals(response,vault.write(admin,write("private-custody-value"),"key"));assertEquals(409,assertThrows(Failure.class,()->vault.write(admin,write("different"),"key")).status());assertEquals(first,store.transaction(admin.tenant(),false,tx->tx.enterprise().secret("provider/token").cipher()));assertEquals(1,graph.codec.model(vault.list(admin).body(),EnterpriseVaultPage.class).items().size());assertFalse(graph.codec.snapshot(admin.tenant(),store.transaction(admin.tenant(),false,tx->tx.load()),false).contains("private-custody-value"));}
 @Test void revocationHidesReferencesAndStopsCredentialReplacement(){authorize();vault.write(admin,write("private"),"key");store.transaction(admin.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var role=graph.codec.model(entries.get(Ids.Kind.ROLE.id("test-recovery-role")).document(),ControlRole.class);put(entries,new ControlRole(role.id(),role.name(),false,role.revision()+1,role.portalRole(),role.roleType(),role.managementRules()));tx.save(before,new Directory(before.revision()+1,entries));return null;});assertEquals(List.of(),graph.codec.model(vault.list(admin).body(),EnterpriseVaultPage.class).items());assertEquals(403,assertThrows(Failure.class,()->vault.write(admin,write("replacement"),"next")).status());}
 @Test void secretDeliveryRequiresConsumedCertificateBoundEffectAndCurrentSameGroupSecretGrants()throws Exception{
    authorize();var resources=List.of(new EnterpriseResourceRule(ResourceKind.FILE,"data",EnterpriseResourceMatch.PREFIX),new EnterpriseResourceRule(ResourceKind.CUSTOM,"secret://provider/token",EnterpriseResourceMatch.EXACT));var base=graph.scope("tools-a","devices-a");var scope=new EnterpriseScope(base.toolGroups(),base.deviceGroups(),base.actions(),base.allActions(),resources,base.conditions());
    graph.grant("human","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,scope);graph.grant("capability","group-a",EnterpriseSourceType.AGENT_GROUP,EnterpriseGrantPurpose.CAPABILITY,scope);graph.grant("human-secret","team-a",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.SECRET,scope);graph.grant("agent-secret","group-a",EnterpriseSourceType.AGENT_GROUP,EnterpriseGrantPurpose.SECRET,scope);
    var toolEntry=graph.entries.get(Ids.Kind.TOOL.id("tool"));var toolNode=(com.fasterxml.jackson.databind.node.ObjectNode)graph.codec.value(toolEntry.document());for(var action:toolNode.path("definition").path("actions"))((com.fasterxml.jackson.databind.node.ArrayNode)action.path("resourceKinds")).add("CUSTOM");graph.entries.put(toolEntry.id(),graph.codec.entry(Ids.Kind.TOOL,graph.codec.json(toolNode)));
    var delegation=graph.codec.model(graph.entries.get(Ids.Kind.DELEGATION.id("delegation")).document(),ControlDelegation.class);graph.add(Ids.Kind.DELEGATION,new ControlDelegation(delegation.id(),delegation.name(),true,delegation.revision()+1,delegation.teamId(),delegation.agentGroupId(),scope));
    var ex=graph.entries.values().stream().filter(e->e.id().kind()==Ids.Kind.EXTRACTOR).findFirst().orElseThrow();var x=graph.codec.model(ex.document(),ControlResourceExtractor.class);graph.add(Ids.Kind.EXTRACTOR,new ControlResourceExtractor(x.id(),x.name(),true,x.revision()+1,x.extractorKind(),x.version(),x.fields(),List.of(new ResourceDescriptor(ResourceKind.CUSTOM,"secret://provider/token")),16L,x.amountPointer(),x.operationPointer()));
    store.transaction(admin.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(tx.load().entries());graph.entries.values().stream().filter(v->v.id().kind()==Ids.Kind.TOOL||v.id().kind()==Ids.Kind.GRANT||v.id().kind()==Ids.Kind.EXTRACTOR||v.id().kind()==Ids.Kind.DELEGATION).forEach(v->entries.put(v.id(),v));tx.save(before,new Directory(before.revision()+1,entries));return null;});vault.write(admin,write("runtime-only-value"),"delivery-key");
    var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var signer=new RsaEffectSigner((java.security.interfaces.RSAPrivateCrtKey)generator.generateKeyPair().getPrivate(),"effect",graph.codec);var ops=new EnterpriseOperations(store,graph.codec,signer,java.time.Clock.fixed(java.time.Instant.ofEpochMilli(EnterpriseConformanceTest.NOW),java.time.ZoneOffset.UTC),"control","toolgate-client",60000,100);
    EnterpriseConformanceTest.online(store,graph.codec,"device");var context=new RequestContext("secret-operation",admin.tenant().value(),RequestMode.DELEGATED,CredentialKind.STATIC_TOKEN,null,"alice","agent","workload",List.of(),1L,1L,null,null,"binding-a",EnterpriseConformanceTest.PROOF);var request=graph.codec.model("{\"toolId\":\"tool\",\"action\":\"read\",\"arguments\":{\"path\":\"data/report.txt\"}}",AuthorizationRequest.class);var directory=store.transaction(admin.tenant(),false,tx->tx.load());var tool=graph.codec.model(directory.entries().get(Ids.Kind.TOOL.id("tool")).document(),ControlTool.class);var gateway=new DirectoryService.Actor(admin.tenant(),"b".repeat(64),false);
    var i=graph.codec.model(ops.submit(gateway,graph.codec.json(new EnterpriseInvocationRequest(context,request,new EnterpriseEvaluator(graph.codec).toolDigest(directory,tool),EnterpriseConformanceTest.DIGEST,null))).body(),EnterpriseInvocation.class);
    String body=graph.codec.json(new EnterpriseSecretDeliveryRequest(i.id(),"provider/token"));
    assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,null,ops)).status());
    var r=graph.codec.model(ops.reserve(gateway,graph.codec.json(new EnterpriseReservationRequest(i.id(),i.revision()))).body(),EnterpriseReservation.class);
    String fence=r.invocation().reservedNonce();var e=r.invocation().evaluation();
    assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,fence,ops)).status(),"An unconsumed reservation is not executing authority");
    ops.consume(admin.tenant(),"device",graph.codec.json(new EnterprisePermitConsumption(i.id(),r.permit(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest())));
    String foreignFence=UUID.randomUUID().toString();
    store.transaction(admin.tenant(),true,tx->{tx.enterprise().reserveNonce(new EnterpriseStore.Nonce(foreignFence,"another-invocation",DirectoryService.digest("another-evaluation"),r.invocation().authorizationEpoch(),EnterpriseConformanceTest.NOW+10000,null));tx.enterprise().consumeNonce(foreignFence,EnterpriseConformanceTest.NOW);return null;});
    for(String invalid:new String[]{null,""," ",foreignFence,"x".repeat(129)})
        assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,invalid,ops)).status());
    assertFalse(store.transaction(admin.tenant(),false,tx->tx.auditPage(0,100)).contains("SECRET_DELIVER"),"Denied fences never deliver or audit a secret");
    var stored=store.transaction(admin.tenant(),false,tx->tx.enterprise().secret("provider/token"));
    store.transaction(admin.tenant(),true,tx->{tx.enterprise().saveSecret(new EnterpriseStore.Secret(stored.name(),stored.toolGroupId(),stored.deviceGroupId(),"invalid-ciphertext"));return null;});
    assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,foreignFence,ops)).status(),"Reject a wrong fence before attempting decryption");
    assertEquals(503,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,fence,ops)).status());
    store.transaction(admin.tenant(),true,tx->{tx.enterprise().saveSecret(stored);return null;});
    assertEquals("runtime-only-value",graph.codec.model(vault.deliver(admin.tenant(),tx->"device",body,fence,ops).body(),EnterpriseSecretDelivery.class).value());
    assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"other-device",body,fence,ops)).status());
    assertEquals(404,assertThrows(Failure.class,()->vault.deliver(new Ids.TenantId("other"),tx->"device",body,fence,ops)).status(),"Another tenant cannot find this invocation");
    // A subsequent attempt invalidates the first fence, even for the same tenant/device/invocation.
    String nextFence=UUID.randomUUID().toString();
    store.transaction(admin.tenant(),true,tx->{var current=tx.enterprise().invocation(i.id());
        tx.enterprise().reserveNonce(new EnterpriseStore.Nonce(nextFence,i.id(),DirectoryService.digest(graph.codec.json(current.evaluation())),current.authorizationEpoch(),EnterpriseConformanceTest.NOW+10000,null));
        tx.enterprise().consumeNonce(nextFence,EnterpriseConformanceTest.NOW);
        tx.enterprise().saveInvocation(new EnterpriseInvocation(current.id(),current.requestDigest(),current.evaluation(),current.state(),current.revision()+1,current.authorizationEpoch(),nextFence,current.expiresAtUnixMs(),current.completedResources(),current.downstreamIdempotencyKey(),current.resultDigest(),current.diagnosticId()),EnterpriseConformanceTest.NOW,current.revision());return null;});
    assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,fence,ops)).status());
    assertEquals("runtime-only-value",graph.codec.model(vault.deliver(admin.tenant(),tx->"device",body,nextFence,ops).body(),EnterpriseSecretDelivery.class).value());
    store.transaction(admin.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());entries.remove(Ids.Kind.GRANT.id("human-secret"));tx.save(before,new Directory(before.revision()+1,entries));return null;});
    assertEquals(403,assertThrows(Failure.class,()->vault.deliver(admin.tenant(),tx->"device",body,nextFence,ops)).status());assertFalse(vault.list(admin).body().contains("runtime-only-value"));
 }

}
