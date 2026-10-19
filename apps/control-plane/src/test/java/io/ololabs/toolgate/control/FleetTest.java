// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FleetTest {
    private final ContractCodec codec=new ContractCodec();
    com.fasterxml.jackson.databind.JsonNode fixtures()throws Exception{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().getParent().resolve("fleet/v1/signed.json").toFile());}
    FleetCrypto crypto()throws Exception{
        var fixture=fixtures();var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();var publicKey=(RSAPublicKey)pair.getPublic();var bytes=publicKey.getModulus().toByteArray();if(bytes[0]==0)bytes=Arrays.copyOfRange(bytes,1,bytes.length);
        var signer=new FleetTrustKey("signer",Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),"AQAB");
        var organization=new ArrayList<FleetTrustKey>();organization.add(signer);organization.add(codec.model(fixture.get("organizationKeys").get(0).toString(),FleetTrustKey.class));
        return new RsaFleetCrypto(codec,(RSAPrivateCrtKey)pair.getPrivate(),"signer",List.of(codec.model(fixture.get("releaseKeys").get(0).toString(),FleetTrustKey.class)),organization,List.of());
    }
    @Test void genuineReleaseTamperWrongTrustAndCanonicalHeaders()throws Exception{
        var crypto=crypto();var release=codec.model(fixtures().get("release").toString(),FleetPackageRelease.class);
        assertEquals(release.packageId(),crypto.release(release.release()).model().packageId());
        var parts=release.release().jws().split("\\.");parts[1]=parts[1].substring(0,parts[1].length()-1)+(parts[1].endsWith("A")?"B":"A");
        assertThrows(Failure.class,()->crypto.release(new FleetSignedDocument(String.join(".",parts))));
        assertThrows(Failure.class,()->crypto.grant(release.release()));
        assertThrows(Failure.class,()->crypto.release(new FleetSignedDocument("e30.e30.c2ln")));
        var grant=new FleetArtifactGrantClaims("fleet","server","device",3L,release.manifestDigest(),release.sizeBytes(),"grant",1700000000000L,1700000060000L);
        assertEquals(grant,crypto.grant(crypto.grant(grant)));
        var document=crypto.release(release.release()).model();FleetService.validatePackage(document);
        assertThrows(Failure.class,()->FleetService.validatePackage(new FleetPackageDocument(document.formatVersion(),document.packageId(),document.version(),document.platforms(),document.architectures(),document.minimumClientVersion(),document.runtimes(),document.tools(),List.of())));
    }
    @Test void durableCanaryAdvanceRollbackUninstallReportsAndTenantIsolation()throws Exception{
        var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));var database="fleet_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=java.sql.DriverManager.getConnection(base,"control_migrator",password);var statement=connection.createStatement()){statement.execute("CREATE DATABASE "+database);}
        var url=base.replace("/control?","/"+database+"?");org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        var store=new PostgresStore(source,codec);var directory=new DirectoryService(store,codec,512,1048576);var tenant=new Ids.TenantId("fleet");var admin=TestSupport.seed(store,codec,tenant,"root");
        directory.mutate(admin,Ids.Kind.USER,"owner","CREATE",DomainTest.user("owner",1),0,"user","request");
        var clock=Clock.fixed(Instant.ofEpochMilli(1700000000000L),ZoneOffset.UTC);
        var endpoint=new EndpointService(store,codec,null,clock,true,"fleet","server","Organization","https://control.example.test","https://gateway.example.test");
        var fleet=new FleetService(store,codec,crypto(),endpoint,clock,true,"fleet","server");
        for(var id:List.of("device-a","device-b")){
            directory.mutate(admin,Ids.Kind.DEVICE,id,"CREATE",codec.json(new ControlDevice(id,id,true,1L,"owner")),0,id,"request");
            store.transaction(tenant,true,tx->{tx.saveEndpoint(new Store.EndpointRecord(id,DirectoryService.digest(id),codec.json(new EndpointDeviceRecord(id,"fleet","owner",DirectoryService.digest(id),EndpointState.ACTIVE,1L,1700000000000L,1L,null,null,null,null,null,null)),"public","0".repeat(64),"{}"));return null;});
        }
        var release=codec.model(fixtures().get("release").toString(),FleetPackageRelease.class);
        var publication=fleet.publish(admin,codec.json(release),"publish","request");assertEquals(publication,fleet.publish(admin,codec.json(release),"publish","request"));
        assertThrows(Failure.class,()->fleet.publish(admin,codec.json(release),"duplicate","request"));
        var request=new FleetRolloutRequest("canary",release.packageId(),release.version(),List.of("device-a","device-b"),true,1L);
        var canary=codec.model(fleet.rollout(admin,codec.json(request),"assign","request").body(),FleetRolloutRecord.class);
        assertEquals(1,canary.members().stream().filter(m->m.generation()>0).count());
        var page=codec.model(fleet.rollouts(admin,null).body(),FleetRolloutPage.class);assertEquals(1L,page.items().getFirst().pending());assertEquals(1L,page.items().getFirst().waiting());
        var advanced=codec.model(fleet.advance(admin,"canary",codec.json(new FleetRolloutAdvance(1L,100L)),"advance","request").body(),FleetRolloutRecord.class);
        assertEquals(2L,advanced.revision());assertThrows(Failure.class,()->fleet.advance(admin,"canary",codec.json(new FleetRolloutAdvance(1L,100L)),"stale","request"));
        assertThrows(Failure.class,()->fleet.rollouts(new DirectoryService.Actor(new Ids.TenantId("other"),"a".repeat(64),true),null));
        store.transaction(tenant,true,tx->{
            var desired=codec.model(tx.fleet().get(FleetStore.Kind.DESIRED,"device-a").document(),FleetDesiredSnapshot.class);
            assertThrows(Failure.class,()->FleetService.validateReport(tx,codec,new ClientReport("device-a","0.9.0-dev",desired.generation(),List.of(new ReportedPackage("wrong","1.0.0",PackageState.READY)),null)));
            assertThrows(Failure.class,()->FleetService.validateReport(tx,codec,new ClientReport("device-a","0.9.0-dev",desired.generation()+1,List.of(),null)));
            FleetService.validateReport(tx,codec,new ClientReport("device-a","0.9.0-dev",0L,List.of(),null));
            return null;
        });
        var race=new FleetRolloutRequest("race",release.packageId(),release.version(),List.of("device-a","device-b"),true,100L);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(var key:List.of("race-a","race-b"))jobs.add(pool.submit(()->{try{fleet.rollout(admin,codec.json(race),key,"request");return true;}catch(Failure failure){assertEquals(409,failure.status());return false;}}));
            int wins=0;for(var job:jobs)if(job.get())wins++;assertEquals(1,wins);
        }finally{pool.shutdownNow();}
        // Audit rejection proves desired generations and rollout inserts roll back together.
        try(var connection=java.sql.DriverManager.getConnection(url,"control_migrator",password);var statement=connection.createStatement()){
            statement.execute("ALTER TABLE control_audit ADD CONSTRAINT reject_fleet CHECK (operation <> 'PACKAGE_ASSIGN' OR target <> 'audit-reject')");
        }
        assertThrows(Failure.class,()->fleet.rollout(admin,codec.json(new FleetRolloutRequest("audit-reject",release.packageId(),release.version(),List.of("device-a"),false,100L)),"audit-reject","request"));
        store.transaction(tenant,false,tx->{assertNull(tx.fleet().get(FleetStore.Kind.ROLLOUT,"audit-reject"));assertEquals(2L,tx.fleet().get(FleetStore.Kind.DESIRED,"device-a").revision());return null;});
        try(var connection=java.sql.DriverManager.getConnection(url,"control_migrator",password);var statement=connection.createStatement()){statement.execute("ALTER TABLE control_audit DROP CONSTRAINT reject_fleet");}
        var uninstall=new FleetRolloutRequest("uninstall",release.packageId(),release.version(),List.of("device-a","device-b"),false,100L);
        fleet.rollout(admin,codec.json(uninstall),"remove","request");
        assertEquals(2L,codec.model(fleet.rollouts(admin,null).body(),FleetRolloutPage.class).items().stream().filter(s->s.rollout().id().equals("canary")).findFirst().orElseThrow().superseded());
        fleet.rollout(admin,codec.json(new FleetRolloutRequest("rollback",release.packageId(),release.version(),List.of("device-a","device-b"),true,100L)),"rollback","request");
        store.transaction(tenant,false,tx->{assertEquals(4L,tx.fleet().get(FleetStore.Kind.DESIRED,"device-a").revision());return null;});
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            TestSupport.scope(connection, tenant);
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("UPDATE control_fleet SET revision=revision+1 WHERE kind='RELEASE'"));
            connection.rollback();
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("DELETE FROM control_fleet"));
        }
    }
}
