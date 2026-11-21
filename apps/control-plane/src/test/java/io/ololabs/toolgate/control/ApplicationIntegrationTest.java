// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.fixtures.Tenants;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real current-schema transactions; graph compilation and outbound HTTPS are explicit injected fixtures. */
final class ApplicationIntegrationTest {
    @TempDir Path temp;
    private final ContractCodec codec=new ContractCodec();
    private final ObjectMapper json=new ObjectMapper();
    private final TenantVaultKeys keys=new TenantVaultKeys(codec,new LocalVaultWrappingProvider("fixture",Map.of("fixture",new byte[32])));
    private static final class Time extends Clock {
        long now=1_800_000_000_000L;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    private static final class Transport implements ApplicationWebhookTransport {
        final List<String> delivered=new ArrayList<>();boolean success=true,crash;
        public void validate(String endpoint){if(!endpoint.equals("https://hooks.example.com/events"))throw Failure.validation();}
        public boolean verify(String endpoint,String subscription,String challenge,byte[] secret){assertEquals(32,secret.length);return success;}
        public boolean deliver(String endpoint,String subscription,ApplicationEvent event,byte[] secret){delivered.add(event.eventId());if(crash)throw new IllegalStateException("Simulated crash before durable ACK");return success;}
    }
    private record Rig(PostgresStore store,Ids.TenantId tenant,DirectoryService.Actor admin,DirectoryService.Actor alice,
                       DirectoryService.Actor bob,WorkflowProfiles profiles,WorkflowManualRuns manual,WorkflowConfiguration config,
                       String digest,Time time,ApplicationEvents events,ApplicationWebhooks webhooks,Transport transport){}
    private Rig rig(boolean postgres)throws Exception {
        javax.sql.DataSource data;
        if(postgres) {
            Assumptions.assumeTrue(System.getenv("CONTROL_TEST_URL")!=null,"Run through the PostgreSQL gate");
            String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD"),name="application_"+UUID.randomUUID().toString().replace("-","");
            try(var c=DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+name);}
            String url=base.replace("/control?","/"+name+"?");org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);data=Tenants.database(source);
        }else data=Tenants.database(SqliteState.open(temp.resolve(UUID.randomUUID()+".sqlite")));
        var store=new PostgresStore(data,codec);var tenant=Tenants.id("tenant");var admin=TestSupport.seed(store,codec,tenant,"root");var time=new Time();
        store.transaction(tenant,true,tx->{
            keys.provision(tenant,tx);var before=tx.load();var entries=new HashMap<>(before.entries());
            TestSupport.put(entries,codec,Ids.Kind.USER,new ControlUser("alice","Alice",true,1L,null,null,null));
            TestSupport.put(entries,codec,Ids.Kind.USER,new ControlUser("bob","Bob",true,1L,null,null,null));
            TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("caller-role","Caller",true,1L,UserRole.BASIC,EnterpriseRoleType.HUMAN,List.of()));
            TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("bot-service","Bot",true,1L,UserRole.BASIC,EnterpriseRoleType.ACTOR_SERVICE,List.of()));
            TestSupport.put(entries,codec,Ids.Kind.TEAM,new ControlTeam("callers","Callers",true,1L,List.of("alice"),List.of("caller-role"),null));
            TestSupport.put(entries,codec,Ids.Kind.AGENT,new ControlAgent("investigator","Investigator",null,true,1L,"root"));
            TestSupport.put(entries,codec,Ids.Kind.AGENT_GROUP,new ControlAgentGroup("support-agents","Support",null,true,1L,List.of("investigator"),List.of("bot-service"),null));
            var all=new GroupSelection(List.of(),true);var c=new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),false,false,null,null);
            var resources=List.of("workflow:investigate","workflow-group:support","agent:investigator","model-group:models").stream()
                .map(s->new EnterpriseResourceRule(ResourceKind.CUSTOM,s,EnterpriseResourceMatch.EXACT)).toList();
            TestSupport.put(entries,codec,Ids.Kind.GRANT,new ControlAccessGrant("human","Human",true,1L,EnterpriseSourceType.ROLE,"caller-role",EnterpriseGrantPurpose.HUMAN,
                new EnterpriseScope(all,all,List.of("workflow.execute","workflow.progress.read","workflow.result.read","workflow.subscribe","agent.execute","model.infer"),false,resources,c)));
            TestSupport.put(entries,codec,Ids.Kind.GRANT,new ControlAccessGrant("service","Service",true,1L,EnterpriseSourceType.ROLE,"bot-service",EnterpriseGrantPurpose.SERVICE,
                new EnterpriseScope(all,all,List.of("workflow.execute","model.infer"),false,resources,c)));
            tx.save(before,new Directory(before.revision()+1,entries));return null;
        });
        var alice=new DirectoryService(store,codec,512,1048576).portalActor(tenant,DirectoryService.digest("alice"),"alice").consoleSession("test-session",time.now+900000,null);
        var bob=new DirectoryService(store,codec,512,1048576).portalActor(tenant,DirectoryService.digest("bob"),"bob").consoleSession("other-session",time.now+900000,null);
        var path=Path.of(System.getProperty("toolgate.fixtures")).getParent().resolve("../v2/valid.json");
        var config=codec.model(json.readTree(Files.readString(path)).path("WorkflowConfiguration").toString(),WorkflowConfiguration.class);
        var profile=json.createObjectNode().put("profileVersion",2).put("profileId","support");profile.putArray("packages");profile.set("workflows",json.valueToTree(config));
        var profiles=new WorkflowProfiles(store,codec,(tx,p,d)->true,keys);
        String digest=json.readTree(profiles.upload(admin,profile.toString(),"stage").body()).path("profileDigest").asText();
        profiles.review(admin,"support",codec.json(Map.of("profileDigest",digest)),"review");
        new WorkflowGraphPreparation(codec,profiles,r->new WorkflowGraphBuildResult(r.tenantId(),r.profileId(),r.profileDigest(),r.workflowId(),r.expectedGeneration(),"sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),List.of("fixture"),"READY"))
            .prepare(admin,"support",codec.json(Map.of("profileDigest",digest,"expectedGeneration",0)),"prepare");
        profiles.publish(admin,"support",codec.json(Map.of("profileDigest",digest,"expectedGeneration",0)),"publish");
        var events=new ApplicationEvents(store,codec,new TriggerService(store,codec,null,null,time,false,tenant.value()),time);
        var transport=new Transport();var webhooks=new ApplicationWebhooks(store,codec,events,transport,keys,time);
        return new Rig(store,tenant,admin,alice,bob,profiles,new WorkflowManualRuns(store,codec,time,profiles,keys),config,digest,time,events,webhooks,transport);
    }
    private String start(Rig rig)throws Exception {
        var body=codec.json(Map.of("profileDigest",rig.digest(),"expectedGeneration",1,"entryAgentId","investigator","message","Private caller input"));
        var response=rig.manual().start(rig.alice(),"support","investigate",body,"submission","request");
        assertEquals(201,response.status());return json.readTree(response.body()).path("runId").asText();
    }
    private String subscribe(Rig rig,String run)throws Exception {
        String body=codec.json(new ApplicationWebhookCreate(ApplicationEventSource.WORKFLOW,run,List.of(ApplicationEventKind.PROGRESS,ApplicationEventKind.RESULT),"https://hooks.example.com/events",rig.time().now+600000,0L));
        var receipt=rig.webhooks().create(rig.alice(),body,"registration");var node=json.readTree(receipt.body());String id=node.path("id").asText();
        assertEquals(id,json.readTree(rig.webhooks().create(rig.alice(),body,"registration").body()).path("id").asText());
        assertEquals(409,assertThrows(Failure.class,()->rig.webhooks().create(rig.alice(),body.replace("\"after\":0","\"after\":1"),"registration")).status());
        var stored=rig.store().transaction(rig.tenant(),false,tx->tx.applicationEvents().get(id));
        assertFalse(stored.secret().contains(node.path("signingSecret").asText()));
        assertFalse(rig.webhooks().get(rig.alice(),id).body().contains("signingSecret"));
        assertEquals(403,assertThrows(Failure.class,()->rig.webhooks().get(rig.bob(),id)).status());
        return id;
    }
    @Test void sqliteUserRunAndDurableDelivery()throws Exception{runAndDelivery(false);}
    @Test void postgresUserRunAndDurableDelivery()throws Exception{runAndDelivery(true);}
    private void runAndDelivery(boolean postgres)throws Exception {
        var rig=rig(postgres);assertFalse(rig.alice().admin());String run=start(rig);
        assertEquals(1,json.readTree(rig.profiles().listPublished(rig.alice(),null,50).body()).size());
        assertEquals(0,json.readTree(rig.profiles().listPublished(rig.bob(),null,50).body()).size());
        var page=rig.events().page(rig.alice(),ApplicationEventSource.WORKFLOW,run,0,Set.of(ApplicationEventKind.PROGRESS,ApplicationEventKind.RESULT));
        assertFalse(page.body().contains("Private caller input"));assertTrue(page.body().contains("QUEUED"));
        assertEquals(403,assertThrows(Failure.class,()->rig.events().page(rig.bob(),ApplicationEventSource.WORKFLOW,run,0,Set.of(ApplicationEventKind.PROGRESS))).status());
        String id=subscribe(rig,run);rig.webhooks().tick(rig.tenant());assertTrue(rig.transport().delivered.isEmpty());
        rig.webhooks().verify(rig.alice(),id);rig.webhooks().tick(rig.tenant());assertEquals(1,rig.transport().delivered.size());
        rig.time().now+=2000;rig.store().transaction(rig.tenant(),true,tx->{WorkflowProgress.control(tx,codec,"worker",run,"RUNNING","progress",null);return null;});
        rig.transport().success=false;rig.webhooks().tick(rig.tenant());String retried=rig.transport().delivered.getLast();
        int attempts=rig.store().transaction(rig.tenant(),false,tx->tx.applicationEvents().get(id).attempts());assertEquals(1,attempts);
        rig.time().now+=3000;rig.transport().success=true;rig.webhooks().tick(rig.tenant());assertEquals(retried,rig.transport().delivered.getLast());
        rig.webhooks().revoke(rig.alice(),id);rig.time().now+=2000;rig.webhooks().tick(rig.tenant());
        assertEquals("REVOKED",rig.store().transaction(rig.tenant(),false,tx->tx.applicationEvents().get(id).state()));
    }
    @Test void sqliteCrashRecoveryAndRevocation()throws Exception{crashAndRevocation(false);}
    @Test void postgresCrashRecoveryAndRevocation()throws Exception{crashAndRevocation(true);}
    @Test void sqliteTerminalEventAndLeaseFencing()throws Exception{terminalAndFencing(false);}
    @Test void postgresTerminalEventAndLeaseFencing()throws Exception{terminalAndFencing(true);}
    private void terminalAndFencing(boolean postgres)throws Exception {
        var rig=rig(postgres);String run=start(rig);String id=subscribe(rig,run);rig.webhooks().verify(rig.alice(),id);
        rig.store().transaction(Tenants.id("other"),true,tx->{
            assertNull(tx.applicationEvents().get(id));
            assertTrue(tx.applicationEvents().page(ApplicationEventSource.WORKFLOW,run,0,200).isEmpty());
            tx.applicationEvents().state(id,"REVOKED");
            tx.applicationEvents().acknowledge(id,"first",999,0);
            return null;
        });
        assertEquals("ACTIVE",rig.store().transaction(rig.tenant(),false,tx->tx.applicationEvents().get(id).state()));
        rig.store().transaction(rig.tenant(),true,tx->{
            var events=tx.applicationEvents();
            assertTrue(events.lease(id,"first",rig.time().now));
            assertFalse(events.lease(id,"second",rig.time().now));
            assertTrue(events.lease(id,"second",rig.time().now+31000));
            events.acknowledge(id,"first",999,0);
            assertEquals(0,events.get(id).cursor());
            events.state(id,"REVOKED");events.acknowledge(id,"second",999,0);
            assertEquals(0,events.get(id).cursor());
            tx.workflowRuns().cancelQueued(run);
            return null;
        });
        var page=json.readTree(rig.events().page(rig.alice(),ApplicationEventSource.WORKFLOW,run,0,Set.of(ApplicationEventKind.RESULT)).body());
        assertEquals(1,page.path("items").size());
        assertEquals("RESULT",page.path("items").get(0).path("kind").asText());
        assertTrue(page.toString().contains("CANCELLED"));assertFalse(page.toString().contains("Private caller input"));
    }
    private void crashAndRevocation(boolean postgres)throws Exception {
        var rig=rig(postgres);String run=start(rig);String id=subscribe(rig,run);rig.webhooks().verify(rig.alice(),id);
        rig.transport().crash=true;assertThrows(IllegalStateException.class,()->rig.webhooks().tick(rig.tenant()));String delivered=rig.transport().delivered.getFirst();
        long cursor=rig.store().transaction(rig.tenant(),false,tx->tx.applicationEvents().get(id).cursor());assertEquals(0L,cursor);
        rig.time().now+=31000;rig.transport().crash=false;rig.webhooks().tick(rig.tenant());assertEquals(delivered,rig.transport().delivered.getLast());
        rig.store().transaction(rig.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());entries.remove(Ids.Kind.GRANT.id("human"));tx.save(before,new Directory(before.revision()+1,entries));return null;});
        int count=rig.transport().delivered.size();rig.time().now+=2000;rig.webhooks().tick(rig.tenant());assertEquals(count,rig.transport().delivered.size());
        assertEquals("REVOKED",rig.store().transaction(rig.tenant(),false,tx->tx.applicationEvents().get(id).state()));
        assertEquals(403,assertThrows(Failure.class,()->rig.events().page(rig.alice(),ApplicationEventSource.WORKFLOW,run,0,Set.of(ApplicationEventKind.PROGRESS))).status());
    }
}
