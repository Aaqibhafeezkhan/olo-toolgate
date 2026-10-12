// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.time.*;
import java.util.*;
import java.security.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;

/** Real issuer/PostgreSQL enrollment, migration, replay, revocation, renewal and race proofs. */
final class EndpointTest {
    private final ContractCodec codec=new ContractCodec();
    private static final long START=1700000000000L;
    private static final class MutableClock extends Clock{
        long time=START;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(time);}public long millis(){return time;}
    }
    private static String pem(Object value)throws IOException{var result=new StringWriter();try(var writer=new JcaPEMWriter(result)){writer.writeObject(value);}return result.toString();}
    private static X509DeviceIssuer issuer()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var pair=generator.generateKeyPair();var subject=new X500Name("O=ToolGate Tests,CN=Test Device Identity CA");
        var builder=new JcaX509v3CertificateBuilder(subject,java.math.BigInteger.ONE,new Date(START-1000),new Date(START+864000000),subject,pair.getPublic());
        builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(0));builder.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.keyCertSign|KeyUsage.cRLSign));
        var holder=builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()));
        return new X509DeviceIssuer(pem(new org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY",pair.getPrivate().getEncoded())),pem(holder),START);
    }
    private static String csr(String device)throws Exception{
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));var pair=generator.generateKeyPair();
        return pem(new JcaPKCS10CertificationRequestBuilder(new X500Name("CN="+device),pair.getPublic()).build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
    }
    private record Setup(EndpointService service,DirectoryService directory,DirectoryService.Actor admin,PostgresStore store,MutableClock clock,X509DeviceIssuer issuer){}
    private Setup setup()throws Exception{
        var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));var database="endpoint_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=java.sql.DriverManager.getConnection(base,"control_migrator",password);var statement=connection.createStatement()){statement.execute("CREATE DATABASE "+database);}
        var url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).target("4").load().migrate();
        assertEquals(17,org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate().migrationsExecuted);
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        return configured(new PostgresStore(source,codec));
    }
    private Setup configured(PostgresStore store)throws Exception{
        var directory=new DirectoryService(store,codec,512,1048576);var admin=TestSupport.seed(store,codec,new Ids.TenantId("endpoint"),"owner");
        for(var user:List.of("other"))directory.mutate(admin,Ids.Kind.USER,user,"CREATE",DomainTest.user(user,1),0,user,"request");
        var clock=new MutableClock();var issuer=issuer();var service=new EndpointService(store,codec,issuer,clock,true,"endpoint","server","Organization","https://control.example.test","https://gateway.example.test");
        service.operations(new EnterpriseOperations(store,codec,null,clock,"control","toolgate-client",60000,256));
        return new Setup(service,directory,admin,store,clock,issuer);
    }
    private EndpointEnrollmentChallenge start(Setup s,String device)throws Exception{return codec.model(s.service.start(codec.json(new EndpointEnrollmentStart(device,"0.6.0-dev",ClientPlatform.LINUX,csr(device),List.of("endpoint.identity.v1"))),"request").body(),EndpointEnrollmentChallenge.class);}
    private EndpointEnrollmentReview review(Setup s,EndpointEnrollmentChallenge challenge){return codec.model(s.service.review(s.admin,"owner",challenge.userCode()).body(),EndpointEnrollmentReview.class);}
    private void decide(Setup s,EndpointEnrollmentChallenge challenge,EnrollmentChoice choice,String key){var review=review(s,challenge);s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(challenge.userCode(),review.keyFingerprint(),choice,START+604800000,null)),key,"request");}
    private EndpointEnrollmentResult poll(Setup s,EndpointEnrollmentChallenge challenge){return codec.model(s.service.poll(codec.json(new EndpointEnrollmentPoll(challenge.enrollmentId(),challenge.deviceCode())),"request").body(),EndpointEnrollmentResult.class);}
    private java.security.cert.X509Certificate certificate(DeviceIdentity identity)throws Exception{return (java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(identity.certificatePem().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}
    @Test void discoveryUsesProductClientVersion()throws Exception{
        var service=new EndpointService(null,codec,issuer(),new MutableClock(),true,"endpoint","server","Organization","https://control.example.test","https://gateway.example.test");
        var envelope=codec.model(service.discovery().body(),SignedClientDiscovery.class);
        var payload=new String(Base64.getUrlDecoder().decode(envelope.payload()),java.nio.charset.StandardCharsets.UTF_8);
        var discovery=codec.model(payload,ClientDiscovery.class);
        assertEquals(System.getProperty("toolgate.productVersion"),discovery.minimumClientVersion());
    }
    @Test void queuedBuildAndDeploymentCannotOutliveTheirCreatorsGroupAuthority()throws Exception{
        var s=setup();
        // This proof exercises a restricted role; an installation owner intentionally retains all management operations.
        s.store.transaction(s.admin.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());var role=codec.model(entries.get(Ids.Kind.ROLE.id("test-recovery-role")).document(),ControlRole.class);
            TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole(role.id(),role.name(),role.enabled(),role.revision()+1,UserRole.ADMINISTRATOR,role.roleType(),role.managementRules()));
            TestSupport.put(entries,codec,Ids.Kind.ROLE,new ControlRole("test-owner-role","Installation owner",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of()));
            TestSupport.put(entries,codec,Ids.Kind.TEAM,new ControlTeam("test-owner-team","Installation owners",true,1L,List.of("other"),List.of("test-owner-role")));
            tx.save(before,new Directory(before.revision()+1,entries));return null;});
        var challenge=start(s,"queue-device");decide(s,challenge,EnrollmentChoice.APPROVE,"approve");var peer=certificate(poll(s,challenge).identity());
        var releaseFixture=new FleetTest();var crypto=releaseFixture.crypto();
        var fleet=new FleetService(s.store,codec,crypto,s.service,s.clock,true,"endpoint","server");
        var builder=new BuilderService(s.store,codec,crypto,fleet,s.service,s.clock,true,"endpoint","server");
        var fixtures=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Path.of(System.getProperty("toolgate.fixtures")).toFile());
        var definition=codec.model(fixtures.get("BuilderDefinition").toString(),BuilderDefinition.class);
        builder.save(s.admin,codec.json(new BuilderDraftRequest("queued-draft",0L,definition)),"draft","request");
        var job=codec.model(builder.test(s.admin,codec.json(new BuilderTestRequest("queued-job","queued-draft",1L,"queue-device",0L)),"test","request").body(),BuilderTestRecord.class);
        assertNotNull(codec.model(builder.poll(peer,"poll").body(),BuilderTestPoll.class).task());
        var release=codec.model(releaseFixture.fixtures().get("release").toString(),FleetPackageRelease.class);
        fleet.publish(s.admin,codec.json(release),"publish","request");
        fleet.rollout(s.admin,codec.json(new FleetRolloutRequest("queued-rollout",release.packageId(),release.version(),List.of("queue-device"),true,100L)),"rollout","request");
        assertEquals(200,fleet.desired(peer).status());
        var grant=codec.model(fleet.grant(peer,codec.json(new FleetArtifactGrantRequest(1L,release.manifestDigest())),"artifact").body(),FleetSignedDocument.class);
        s.store.transaction(s.admin.tenant(),true,tx->{assertEquals(release,fleet.authorizeDownload(tx,peer,grant));return null;});
        var role=codec.model(s.directory.get(s.admin,Ids.Kind.ROLE,"test-recovery-role").body(),ControlRole.class);
        var rules=role.managementRules().stream().map(r->new EnterpriseManagementRule(r.actions().stream().filter(a->!Set.of("build","deploy").contains(a)).toList(),r.groupType(),r.groups(),r.grantableScopes(),r.conditions())).toList();
        var revoked=new ControlRole(role.id(),role.name(),role.enabled(),role.revision(),role.portalRole(),role.roleType(),rules);
        s.directory.mutate(s.admin,Ids.Kind.ROLE,role.id(),"UPDATE",codec.json(revoked),role.revision(),"revoke-build-deploy","request");
        assertNull(codec.model(builder.poll(peer,"revoked-poll").body(),BuilderTestPoll.class).task());
        assertEquals(403,assertThrows(Failure.class,()->builder.result(peer,codec.json(new BuilderTestResult(job.id(),job.leaseId(),job.definitionDigest(),true,null)),"revoked-result")).status());
        assertEquals(403,assertThrows(Failure.class,()->fleet.desired(peer)).status());
        assertEquals(403,assertThrows(Failure.class,()->s.store.transaction(s.admin.tenant(),true,tx->fleet.authorizeDownload(tx,peer,grant))).status());
        assertTrue(s.store.transaction(s.admin.tenant(),false,tx->tx.auditPage(0,100)).contains("BUILDER_AUTHORITY_REVOKED"));
    }
    @Test void enrollmentBindsOwnerKeyAndDurableSequenceAndRevocation()throws Exception{
        var s=setup();var challenge=start(s,"device");var review=review(s,challenge);assertEquals(EnrollmentState.PENDING,poll(s,challenge).state());
        assertEquals(403,assertThrows(Failure.class,()->s.service.review(new DirectoryService.Actor(new Ids.TenantId("other"),"a".repeat(64),true),"owner",challenge.userCode())).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.review(s.admin,"unknown",challenge.userCode())).status());
        assertEquals(409,assertThrows(Failure.class,()->s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(challenge.userCode(),"0".repeat(64),EnrollmentChoice.APPROVE,null,null)),"wrong-key","request")).status());
        decide(s,challenge,EnrollmentChoice.APPROVE,"approve");decide(s,challenge,EnrollmentChoice.APPROVE,"approve");
        assertEquals(409,assertThrows(Failure.class,()->decide(s,challenge,EnrollmentChoice.DENY,"other-key")).status());
        s.clock.time+=5000;var result=poll(s,challenge);assertEquals(EnrollmentState.CONSUMED,result.state());var identity=result.identity();assertEquals("owner",identity.userId());assertEquals("endpoint",identity.tenantId());
        var peer=certificate(identity);assertEquals(review.keyFingerprint(),s.issuer.peerFingerprint(peer,s.clock.time));assertEquals(-1,peer.getBasicConstraints());
        s.service.verifySocketPeer(peer);
        assertEquals(List.of("1.3.6.1.5.5.7.3.2"),peer.getExtendedKeyUsage());
        s.clock.time+=5000;assertEquals(identity,poll(s,challenge).identity());
        var report=new ClientReport("device","0.6.0-dev",0L,List.of());var body=codec.json(new EndpointCheckIn(1L,report,null,null));
        var ack=s.service.checkIn(peer,body,"request");assertEquals(ack.body(),s.service.checkIn(peer,body,"retry").body());
        assertEquals(409,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(2L,report,null,null)),"too-fast")).status());
        assertEquals(409,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(1L,new ClientReport("device","0.6.1",0L,List.of()),null,null)),"changed")).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(2L,new ClientReport("other","0.6.0-dev",0L,List.of()),null,null)),"wrong")).status());
        assertEquals(409,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(3L,report,null,null)),"gap")).status());
        assertFalse(ack.body().contains("nextIntervalMs"));
        var optedIn=codec.model(s.service.checkIn(peer,body,"millisecond-retry",true).body(),EndpointCheckInAck.class);
        assertEquals(500L,optedIn.nextIntervalMs());
        assertEquals(ack.body(),s.service.checkIn(peer,body,"legacy-retry").body());
        s.clock.time+=249;
        assertEquals(409,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(2L,report,null,null)),"burst",true)).status());
        s.clock.time+=1;
        var fast=codec.model(s.service.checkIn(peer,codec.json(new EndpointCheckIn(2L,report,null,null)),"millisecond",true).body(),EndpointCheckInAck.class);
        assertEquals(2L,fast.nextIntervalSeconds());
        assertEquals(500L,fast.nextIntervalMs());
        // A configured check-in changes the negotiated interval and scales the burst guard with it.
        s.service.checkInPolicy(ClientCheckInPolicy.configure(4000L,0,null));
        s.clock.time+=1999;
        assertEquals(409,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(3L,report,null,null)),"configured-burst",true)).status());
        s.clock.time+=1;
        var configured=codec.model(s.service.checkIn(peer,codec.json(new EndpointCheckIn(3L,report,null,null)),"configured",true).body(),EndpointCheckInAck.class);
        assertEquals(4000L,configured.nextIntervalMs());
        assertEquals(4L,configured.nextIntervalSeconds());
        s.service.checkInPolicy(ClientCheckInPolicy.QUICKSTART);
        s.clock.time=START+50000000;var renewed=codec.model(s.service.checkIn(peer,codec.json(new EndpointCheckIn(4L,report,null,null)),"renew").body(),EndpointCheckInAck.class).identity();assertNotNull(renewed);assertTrue(renewed.expiresAtUnixMs()>identity.expiresAtUnixMs());
        var device=codec.model(s.service.device(s.admin,"device").body(),EndpointDeviceRecord.class);assertEquals(4,device.reportSequence());
        var revoked=s.service.revoke(s.admin,"device",codec.json(new EndpointRevokeRequest(device.revision())),"revoke","request");assertEquals(revoked.body(),s.service.revoke(s.admin,"device",codec.json(new EndpointRevokeRequest(device.revision())),"revoke","request").body());
        assertEquals(403,assertThrows(Failure.class,()->s.service.checkIn(certificate(renewed),codec.json(new EndpointCheckIn(5L,report,null,null)),"revoked")).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.checkIn(peer,body,"replay-after-revoke")).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.verifySocketPeer(peer)).status());
        var audit=s.directory.audit(s.admin,0,100).body();for(var event:List.of("ENROLLMENT_CREATE","ENROLLMENT_APPROVE","ENROLLMENT_CONSUME","DEVICE_CHECK_IN","DEVICE_RENEW","DEVICE_REVOKE"))assertTrue(audit.contains(event));
        assertFalse(audit.contains(challenge.deviceCode()));assertFalse(s.directory.export(s.admin,false).body().contains("certificatePem"));
    }
    @Test void deniedExpiredWrongCodeInvalidCsrAndDisabledOwnersFailClosed()throws Exception{
        var s=setup();var denied=start(s,"denied");decide(s,denied,EnrollmentChoice.DENY,"deny");assertEquals(EnrollmentState.DENIED,poll(s,denied).state());
        var pending=start(s,"pending");assertEquals(403,assertThrows(Failure.class,()->s.service.poll(codec.json(new EndpointEnrollmentPoll(pending.enrollmentId(),"f".repeat(64))),"wrong")).status());
        assertThrows(Failure.class,()->s.service.start(codec.json(new EndpointEnrollmentStart("bad","0.6.0-dev",ClientPlatform.LINUX,"not a CSR",List.of())),"invalid"));
        var request=csr("tampered");var parsed=new org.bouncycastle.util.io.pem.PemReader(new StringReader(request)).readPemObject();
        byte[] corrupted=parsed.getContent();corrupted[corrupted.length-1]^=1;
        var text=new StringWriter();try(var writer=new org.bouncycastle.util.io.pem.PemWriter(text)){writer.writeObject(new org.bouncycastle.util.io.pem.PemObject(parsed.getType(),corrupted));}
        assertThrows(Failure.class,()->s.issuer.fingerprint(text.toString()));
        s.clock.time=pending.expiresAtUnixMs();assertEquals(EnrollmentState.EXPIRED,poll(s,pending).state());assertEquals(409,assertThrows(Failure.class,()->decide(s,pending,EnrollmentChoice.APPROVE,"expired")).status());
        s.clock.time-=1;assertEquals(503,assertThrows(Failure.class,()->s.service.start(codec.json(new EndpointEnrollmentStart("regression","0.6.0-dev",ClientPlatform.LINUX,request,List.of())),"clock")).status());
    }
    @Test void competingDecisionsHaveOneWinnerAndAuditFailureRollsBack()throws Exception{
        var s=setup();var challenge=start(s,"race");var review=review(s,challenge);var body=codec.json(new EndpointEnrollmentDecision(challenge.userCode(),review.keyFingerprint(),EnrollmentChoice.APPROVE,null,null));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var barrier=new java.util.concurrent.CyclicBarrier(2);
        try{var tasks=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++){final int n=i;tasks.add(pool.submit(()->{barrier.await();try{s.service.decide(s.admin,"owner",body,"race-"+n,"request");return true;}catch(Failure failure){assertEquals(409,failure.status());return false;}}));}
            int winners=0;for(var task:tasks)if(task.get())winners++;assertEquals(1,winners);
        }finally{pool.shutdownNow();}
        var second=start(s,"audit");var secondReview=review(s,second);
        Store failing=new Store(){public<T>T transaction(Ids.TenantId tenant,boolean write,java.util.function.Function<Session,T> work){return s.store.transaction(tenant,write,tx->work.apply((Session)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Session.class},(proxy,method,args)->{if(method.getName().equals("audit"))throw Failure.unavailable();try{return method.invoke(tx,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}})));}};
        var service=new EndpointService(failing,codec,s.issuer,s.clock,true,"endpoint","server","Organization","https://control.example.test","https://gateway.example.test");
        assertThrows(Failure.class,()->service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(second.userCode(),secondReview.keyFingerprint(),EnrollmentChoice.APPROVE,null,null)),"audit-fail","request"));
        assertEquals(EnrollmentState.PENDING,review(s,second).state());assertThrows(Failure.class,()->s.service.device(s.admin,"audit"));
    }
    private static io.ololabs.toolgate.contracts.v2.ControlServerSettings settings(boolean auto,long days,String owner,String name){
        return new io.ololabs.toolgate.contracts.v2.ControlServerSettings(2L,1L,new io.ololabs.toolgate.contracts.v2.GatewaySettings(name),
            new io.ololabs.toolgate.contracts.v2.DeviceApproval(auto,days*ServerSettingsService.DAY_MS,owner),ClientCheckInPolicy.QUICKSTART.effective());
    }
    @Test void autoApprovalUsesSettingsAndSqlite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        var s=configured(new PostgresStore(SqliteState.open(directory.resolve("auto.sqlite")),codec));
        var policy=ClientCheckInPolicy.configure(2000L,20,null);s.service.checkInPolicy(policy);
        var settings=new ServerSettingsService(s.store,codec,policy);
        var defaults=codec.model(settings.get(s.admin).body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
        assertEquals(2L,defaults.formatVersion());assertEquals(1L,defaults.revision());assertFalse(defaults.deviceApproval().autoApprove());
        assertEquals(30L*ServerSettingsService.DAY_MS,defaults.deviceApproval().durationMs());assertEquals(ServerSettingsService.DEFAULT_GATEWAY_NAME,defaults.gateway().gatewayName());
        // Check-in is the running env policy, read-only.
        assertEquals(policy.effective(),defaults.clientCheckIn());
        assertEquals(403,assertThrows(Failure.class,()->settings.get(new DirectoryService.Actor(s.admin.tenant(),"a".repeat(64),false))).status());
        assertEquals(400,assertThrows(Failure.class,()->settings.update(s.admin,codec.json(settings(true,30,null,"Lab Gateway")),1,"no-owner","request")).status());
        assertEquals(400,assertThrows(Failure.class,()->settings.update(s.admin,codec.json(settings(true,30,"missing","Lab Gateway")),1,"missing-owner","request")).status());
        var manual=start(s,"manual");assertEquals(EnrollmentState.PENDING,review(s,manual).state());assertEquals(ServerSettingsService.DEFAULT_GATEWAY_NAME,poll(s,manual).serverName());
        // Each Configuration page patches only its own group.
        var gateway="{\"gateway\":{\"gatewayName\":\"Lab Gateway\"}}";
        var renamed=settings.patch(s.admin,gateway,1,"rename","request");
        var afterRename=codec.model(renamed.body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
        assertEquals(2L,afterRename.revision());assertEquals("Lab Gateway",afterRename.gateway().gatewayName());assertFalse(afterRename.deviceApproval().autoApprove());
        assertEquals(renamed,settings.patch(s.admin,gateway,1,"rename","retry"));
        assertEquals(409,assertThrows(Failure.class,()->settings.patch(s.admin,gateway,1,"stale","request")).status());
        assertEquals(400,assertThrows(Failure.class,()->settings.patch(s.admin,"{\"clientCheckIn\":{\"checkInMs\":500,\"jitterPct\":0,\"offlineAfterMs\":120000,\"source\":\"DATABASE\"}}",2,"env-owned","request")).status());
        assertEquals(400,assertThrows(Failure.class,()->settings.patch(s.admin,"{\"deviceApproval\":{\"autoApprove\":true}}",2,"patch-no-owner","request")).status());
        var saved=settings.patch(s.admin,"{\"deviceApproval\":{\"autoApprove\":true,\"ownerUserId\":\"owner\"}}",2,"enable","request");
        var enabled=codec.model(saved.body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
        assertEquals(3L,enabled.revision());assertTrue(enabled.deviceApproval().autoApprove());assertEquals("Lab Gateway",enabled.gateway().gatewayName());
        assertEquals(30L*ServerSettingsService.DAY_MS,enabled.deviceApproval().durationMs());
        var automatic=start(s,"automatic");var result=poll(s,automatic);
        assertEquals(EnrollmentState.CONSUMED,result.state());assertNotNull(result.identity());assertEquals("Lab Gateway",result.serverName());
        var device=codec.model(s.service.device(s.admin,"automatic").body(),EndpointDeviceRecord.class);
        assertEquals("owner",device.userId());assertEquals(START+30L*86400000,device.connectionExpiresAtUnixMs());assertTrue(device.connectionApproved());
        assertEquals(List.of(manual.userCode()),codec.model(s.service.pending(s.admin,"owner").body(),EndpointEnrollmentPage.class).items().stream().map(EndpointEnrollmentReview::userCode).toList());
        // Restoring an export replaces the record; its check-in values are ignored.
        var exported=codec.model(settings.get(s.admin).body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
        var foreign=new io.ololabs.toolgate.contracts.v2.ControlServerSettings(2L,3L,exported.gateway(),new io.ololabs.toolgate.contracts.v2.DeviceApproval(false,7L*ServerSettingsService.DAY_MS,null),ClientCheckInPolicy.QUICKSTART.effective());
        var restored=codec.model(settings.update(s.admin,codec.json(foreign),3,"restore","request").body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
        assertEquals(4L,restored.revision());assertFalse(restored.deviceApproval().autoApprove());assertEquals(policy.effective(),restored.clientCheckIn());
        assertEquals(400,assertThrows(Failure.class,()->settings.update(s.admin,"{\"formatVersion\":1,\"revision\":4,\"autoApproveDevices\":false,\"autoApproveDurationDays\":7}",4,"v1","request")).status());
        assertEquals(400,assertThrows(Failure.class,()->settings.patch(s.admin,"{\"gateway\":{\"gatewayName\":\" spaced\"}}",4,"bad-name","request")).status());
        assertEquals(400,assertThrows(Failure.class,()->settings.patch(s.admin,"{\"deviceApproval\":{\"autoApprove\":false,\"durationMs\":1000}}",4,"too-short","request")).status());
        // Startup import seeds only once unless overwrite is requested.
        assertFalse(settings.importAtStartup(s.admin.tenant(),settings(false,7,null,"Lab Gateway"),false,"startup"));
        assertTrue(settings.importAtStartup(s.admin.tenant(),settings(false,7,null,"Imported"),true,"startup"));
        var imported=codec.model(settings.get(s.admin).body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
        assertEquals(5L,imported.revision());assertEquals(7L*ServerSettingsService.DAY_MS,imported.deviceApproval().durationMs());assertEquals("Imported",imported.gateway().gatewayName());
        assertEquals(EnrollmentState.PENDING,review(s,start(s,"after-off")).state());
        assertEquals(30L,ConfigurationImportBootstrap.days("30Day"));assertEquals(7L,ConfigurationImportBootstrap.days("7"));
        assertThrows(IllegalStateException.class,()->ConfigurationImportBootstrap.days("month"));
    }
    @Test void pendingDeviceListAndConnectionDeadlinesUsePostgres()throws Exception{deadlineFlow(setup());}
    @Test void pendingDeviceListAndConnectionDeadlinesUseSqlite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        deadlineFlow(configured(new PostgresStore(SqliteState.open(directory.resolve("deadline.sqlite")),codec)));
    }
    private void deadlineFlow(Setup s)throws Exception{
        var first=start(s,"timed");var second=start(s,"second");var denied=start(s,"denied");var legacy=start(s,"legacy-caller");
        assertEquals(4,codec.model(s.service.pending(s.admin,"owner").body(),EndpointEnrollmentPage.class).items().size());
        assertEquals(403,assertThrows(Failure.class,()->s.service.pending(new DirectoryService.Actor(new Ids.TenantId("other"),"b".repeat(64),true),"owner")).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.pending(s.admin,"unknown")).status());
        var review=review(s,first);long until=START+108000000; // 30 hours, longer than one certificate lifetime.
        for(long invalid:List.of(START,START-1))assertEquals(400,assertThrows(Failure.class,()->s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(first.userCode(),review.keyFingerprint(),EnrollmentChoice.APPROVE,invalid,null)),"invalid-"+invalid,"request")).status());
        var body=codec.json(new EndpointEnrollmentDecision(first.userCode(),review.keyFingerprint(),EnrollmentChoice.APPROVE,until,null));
        var approved=s.service.decide(s.admin,"owner",body,"timed-approval","request");
        assertEquals(until,codec.model(approved.body(),EndpointEnrollmentReview.class).connectionExpiresAtUnixMs());
        assertEquals(approved,s.service.decide(s.admin,"owner",body,"timed-approval","retry"));
        decide(s,denied,EnrollmentChoice.DENY,"deny");
        var legacyReview=review(s,legacy);s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(legacy.userCode(),legacyReview.keyFingerprint(),EnrollmentChoice.APPROVE,null,null)),"legacy","request");
        assertEquals(START+86400000,codec.model(s.service.device(s.admin,"legacy-caller").body(),EndpointDeviceRecord.class).connectionExpiresAtUnixMs());
        var page=s.service.pending(s.admin,"owner").body();assertEquals(List.of(second.userCode()),codec.model(page,EndpointEnrollmentPage.class).items().stream().map(EndpointEnrollmentReview::userCode).toList());
        for(String secret:List.of(first.deviceCode(),"csrPem","certificatePem","deviceCode"))assertFalse(page.contains(secret));
        var identity=poll(s,first).identity();assertEquals(START+86400000,identity.expiresAtUnixMs());var peer=certificate(identity);
        var report=new ClientReport("timed","0.6.0-dev",0L,List.of());
        s.clock.time=START+50000000;
        var renewed=codec.model(s.service.checkIn(peer,codec.json(new EndpointCheckIn(1L,report,null,null)),"renew").body(),EndpointCheckInAck.class).identity();
        assertNotNull(renewed);assertEquals(until,renewed.expiresAtUnixMs());assertEquals(until,certificate(renewed).getNotAfter().getTime());
        s.clock.time+=500;
        assertNull(codec.model(s.service.checkIn(certificate(renewed),codec.json(new EndpointCheckIn(2L,report,null,null)),"capped").body(),EndpointCheckInAck.class).identity());
        var device=codec.model(s.service.device(s.admin,"timed").body(),EndpointDeviceRecord.class);assertEquals(until,device.connectionExpiresAtUnixMs());
        assertTrue(codec.model(s.service.pending(s.admin,"owner").body(),EndpointEnrollmentPage.class).items().isEmpty());
        s.clock.time=until;
        assertThrows(Failure.class,()->s.service.verifySocketPeer(certificate(renewed)));
        assertThrows(Failure.class,()->s.service.checkIn(certificate(renewed),codec.json(new EndpointCheckIn(2L,report,null,null)),"expired-replay"));
        assertEquals(423,assertThrows(Failure.class,()->s.store.transaction(s.admin.tenant(),true,tx->{s.service.active(tx,device);return null;})).status());
        assertEquals(approved,s.service.decide(s.admin,"owner",body,"timed-approval","late-replay"));
        assertEquals(until,codec.model(s.service.device(s.admin,"timed").body(),EndpointDeviceRecord.class).connectionExpiresAtUnixMs());
    }
    @Test void unlimitedApprovalAndReversibleManagementUsePostgres()throws Exception{managementFlow(setup());}
    @Test void presenceDeletionAndExpiredApprovalUsePostgres()throws Exception{presenceFlow(setup());}
    @Test void presenceDeletionAndExpiredApprovalUseSqlite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        presenceFlow(configured(new PostgresStore(SqliteState.open(directory.resolve("presence.sqlite")),codec)));
    }
    private void presenceFlow(Setup s)throws Exception{
        String csr=csr("live");String announcement=codec.json(new EndpointEnrollmentStart("live","0.10.0-dev",ClientPlatform.LINUX,csr,List.of()));
        String presence=codec.json(new EndpointPresencePing(codec.model(announcement,EndpointEnrollmentStart.class),"+00:00"));
        assertEquals(EndpointConnectionAction.ENROLL,codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class).action());
        var challenge=codec.model(s.service.start(announcement,"first-enrollment").body(),EndpointEnrollmentChallenge.class);
        decide(s,challenge,EnrollmentChoice.APPROVE,"first-approval");var identity=poll(s,challenge).identity();
        var report=new ClientReport("live","0.10.0-dev",0L,List.of());
        s.service.checkIn(certificate(identity),codec.json(new EndpointCheckIn(1L,report,null,null)),"first-check-in");
        var directory=codec.model(s.directory.get(s.admin,Ids.Kind.DEVICE,"live").body(),ControlDevice.class);
        s.directory.mutate(s.admin,Ids.Kind.DEVICE,"live","DELETE",null,directory.revision(),"delete-live","request");
        var ping=codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class);
        assertEquals(EndpointState.UNENROLLED,ping.state());assertEquals(EndpointConnectionAction.ENROLL,ping.action());assertEquals(1L,ping.reportSequence());
        assertEquals(410,assertThrows(Failure.class,()->s.service.checkIn(certificate(identity),codec.json(new EndpointCheckIn(2L,report,null,null)),"deleted-check-in")).status());
        assertEquals(403,assertThrows(Failure.class,()->poll(s,challenge)).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.ping(codec.json(new EndpointPresencePing(new EndpointEnrollmentStart("live","0.10.0-dev",ClientPlatform.LINUX,csr("impostor"),List.of()),"+00:00")))).status());
        var fresh=codec.model(s.service.start(announcement,"reenroll").body(),EndpointEnrollmentChallenge.class);
        assertEquals(EnrollmentState.PENDING,review(s,fresh).state());
        var listed=codec.model(s.service.devices(s.admin).body(),EndpointManagedDevicePage.class).items().getFirst();
        assertEquals("live",listed.deviceId());assertNull(listed.endpointDevice());assertNull(listed.directoryDevice());assertNotNull(listed.enrollment());
        assertEquals(EndpointState.PENDING,codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class).state());
        s.service.enabled(s.admin,"owner","live",codec.json(new EndpointEnabledRequest(0L,false)),"disable-pending","request");
        assertEquals(410,assertThrows(Failure.class,()->s.service.verifySocketPeer(certificate(identity))).status());
        s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(fresh.userCode(),review(s,fresh).keyFingerprint(),EnrollmentChoice.APPROVE,s.clock.time+60000,null)),"fresh-approval","request");
        assertEquals(EndpointConnectionAction.WAIT,codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class).action());
        s.service.enabled(s.admin,"owner","live",codec.json(new EndpointEnabledRequest(1L,true)),"enable-fresh","request");
        var freshIdentity=poll(s,fresh).identity();s.clock.time+=500;
        s.service.checkIn(certificate(freshIdentity),codec.json(new EndpointCheckIn(2L,report,null,null)),"fresh-check-in");
        assertEquals(EndpointConnectionAction.CHECK_IN,codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class).action());
        s.clock.time=START+60000;
        for(int i=0;i<2;i++)assertEquals(EndpointConnectionAction.WAIT,codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class).action());
        listed=codec.model(s.service.devices(s.admin).body(),EndpointManagedDevicePage.class).items().getFirst();
        assertNotNull(listed.endpointDevice());assertEquals(START+60000,listed.endpointDevice().connectionExpiresAtUnixMs());
        s.service.approval(s.admin,"owner","live",codec.json(new EndpointApprovalRequest(listed.endpointDevice().approvalRevision(),true,null,true)),"renew-approval","request");
        assertEquals(EndpointConnectionAction.CHECK_IN,codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class).action());
        var endpoint=codec.model(s.service.device(s.admin,"live").body(),EndpointDeviceRecord.class);
        s.service.revoke(s.admin,"live",codec.json(new EndpointRevokeRequest(endpoint.revision())),"retire-key","request");
        for(int i=0;i<2;i++){
            ping=codec.model(s.service.ping(presence).body(),EndpointConnectionDirective.class);assertEquals(EndpointState.REVOKED,ping.state());assertEquals(EndpointConnectionAction.WAIT,ping.action());
        }
        directory=codec.model(s.directory.get(s.admin,Ids.Kind.DEVICE,"live").body(),ControlDevice.class);
        s.directory.mutate(s.admin,Ids.Kind.DEVICE,"live","DELETE",null,directory.revision(),"delete-revoked","request");
        assertEquals(403,assertThrows(Failure.class,()->s.service.start(announcement,"revoked-reenrollment")).status());
    }
    @Test void revokedDeviceCanExplicitlyRequestFreshApprovalUsePostgres()throws Exception{revocationRequestFlow(setup());}
    @Test void revokedDeviceCanExplicitlyRequestFreshApprovalUseSqlite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        revocationRequestFlow(configured(new PostgresStore(SqliteState.open(directory.resolve("retry.sqlite")),codec)));
    }
    private void revocationRequestFlow(Setup s)throws Exception{
        for(boolean fromClient:List.of(true,false)){
            String id=fromClient?"retry-client":"retry-admin";String csr=csr(id);
            var settings=new ServerSettingsService(s.store,codec,ClientCheckInPolicy.QUICKSTART);
            var current=codec.model(settings.get(s.admin).body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
            settings.patch(s.admin,"{\"deviceApproval\":{\"autoApprove\":false}}",current.revision(),"manual-"+id,"request");
            var announcement=new EndpointEnrollmentStart(id,"0.10.0-dev",ClientPlatform.LINUX,csr,List.of());
            var original=codec.model(s.service.start(codec.json(announcement),"initial-"+id).body(),EndpointEnrollmentChallenge.class);
            decide(s,original,EnrollmentChoice.APPROVE,"approve-"+id);var oldIdentity=poll(s,original).identity();
            var report=new ClientReport(id,"0.10.0-dev",0L,List.of());
            s.service.checkIn(certificate(oldIdentity),codec.json(new EndpointCheckIn(1L,report,null,null)),"report-"+id);
            var device=codec.model(s.service.device(s.admin,id).body(),EndpointDeviceRecord.class);
            s.service.revoke(s.admin,id,codec.json(new EndpointRevokeRequest(device.revision())),"revoke-"+id,"request");
            var intervals=Map.of("+00:00",2800000L,"+05:30",1000000L,"+05:45",100000L,"-03:30",1000000L);
            for(var interval:intervals.entrySet()){
                var ping=codec.model(s.service.ping(codec.json(new EndpointPresencePing(announcement,interval.getKey()))).body(),EndpointConnectionDirective.class);
                assertEquals(EndpointState.REVOKED,ping.state());assertEquals(EndpointConnectionAction.WAIT,ping.action());
                assertEquals(interval.getValue()-(s.clock.millis()-START),ping.nextIntervalMs());
            }
            assertEquals(403,assertThrows(Failure.class,()->poll(s,original)).status());
            assertEquals(403,assertThrows(Failure.class,()->s.service.start(codec.json(announcement),"automatic-retry-"+id)).status());
            current=codec.model(settings.get(s.admin).body(),io.ololabs.toolgate.contracts.v2.ControlServerSettings.class);
            settings.patch(s.admin,"{\"deviceApproval\":{\"autoApprove\":true,\"ownerUserId\":\"owner\"}}",current.revision(),"auto-"+id,"request");
            EndpointEnrollmentChallenge fresh;
            if(fromClient){
                var impostor=new EndpointEnrollmentStart(id,"0.10.0-dev",ClientPlatform.LINUX,csr("impostor"),List.of());
                assertEquals(403,assertThrows(Failure.class,()->s.service.requestApproval(codec.json(impostor),"wrong-key")).status());
                fresh=codec.model(s.service.requestApproval(codec.json(announcement),"explicit-retry").body(),EndpointEnrollmentChallenge.class);
            }else{
                device=codec.model(s.service.device(s.admin,id).body(),EndpointDeviceRecord.class);
                String request=codec.json(new EndpointRevokeRequest(device.revision()));
                assertEquals(403,assertThrows(Failure.class,()->s.service.requestApproval(new DirectoryService.Actor(s.admin.tenant(),"a".repeat(64),false),"owner",id,request,"no-role","request")).status());
                assertEquals(409,assertThrows(Failure.class,()->s.service.requestApproval(s.admin,"owner",id,codec.json(new EndpointRevokeRequest(1L)),"stale-retry","request")).status());
                var reset=s.service.requestApproval(s.admin,"owner",id,request,"admin-retry","request");
                assertEquals(reset,s.service.requestApproval(s.admin,"owner",id,request,"admin-retry","retry"));
                var awaiting=codec.model(reset.body(),EndpointDeviceRecord.class);assertFalse(awaiting.connectionApproved());
                assertEquals(409,assertThrows(Failure.class,()->s.service.approval(s.admin,"owner",id,codec.json(new EndpointApprovalRequest(awaiting.approvalRevision(),true,null,true)),"skip-review","request")).status());
                fresh=codec.model(s.service.start(codec.json(announcement),"fresh-admin-request").body(),EndpointEnrollmentChallenge.class);
            }
            assertEquals(EnrollmentState.PENDING,review(s,fresh).state());assertNull(poll(s,fresh).identity());
            assertEquals(410,assertThrows(Failure.class,()->s.service.verifySocketPeer(certificate(oldIdentity))).status());
            var listed=codec.model(s.service.devices(s.admin).body(),EndpointManagedDevicePage.class).items().stream().filter(row->row.deviceId().equals(id)).findFirst().orElseThrow();
            assertNotNull(listed.enrollment());assertNull(listed.endpointDevice());
            decide(s,fresh,EnrollmentChoice.APPROVE,"fresh-approve-"+id);s.clock.time+=5001;var identity=poll(s,fresh).identity();
            assertNotNull(identity);s.service.checkIn(certificate(identity),codec.json(new EndpointCheckIn(2L,report,null,null)),"fresh-report-"+id);
            assertEquals(EndpointConnectionAction.CHECK_IN,codec.model(s.service.ping(codec.json(new EndpointPresencePing(announcement,"+05:30"))).body(),EndpointConnectionDirective.class).action());
        }
    }
    @Test void unlimitedApprovalAndReversibleManagementUseSqlite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        managementFlow(configured(new PostgresStore(SqliteState.open(directory.resolve("managed.sqlite")),codec)));
    }
    private void managementFlow(Setup s)throws Exception{
        var challenge=start(s,"managed");var review=review(s,challenge);
        assertEquals(1,codec.model(s.service.devices(s.admin).body(),EndpointManagedDevicePage.class).items().size());
        assertEquals(403,assertThrows(Failure.class,()->s.service.devices(new DirectoryService.Actor(new Ids.TenantId("other"),"a".repeat(64),true))).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.devices(new DirectoryService.Actor(s.admin.tenant(),"a".repeat(64),false))).status());
        var disabled=codec.model(s.service.enabled(s.admin,"owner","managed",codec.json(new EndpointEnabledRequest(0L,false)),"pending-disable","request").body(),ControlDevice.class);
        assertFalse(disabled.enabled());assertEquals("owner",disabled.ownerUserId());
        assertThrows(Failure.class,()->s.service.device(s.admin,"managed"));
        assertEquals(400,assertThrows(Failure.class,()->s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(challenge.userCode(),review.keyFingerprint(),EnrollmentChoice.APPROVE,START+60000,true)),"ambiguous","request")).status());
        var approved=s.service.decide(s.admin,"owner",codec.json(new EndpointEnrollmentDecision(challenge.userCode(),review.keyFingerprint(),EnrollmentChoice.APPROVE,null,true)),"approve-unlimited","request");
        assertTrue(codec.model(approved.body(),EndpointEnrollmentReview.class).unlimitedConnection());
        assertEquals(423,assertThrows(Failure.class,()->poll(s,challenge)).status());
        s.service.enabled(s.admin,"owner","managed",codec.json(new EndpointEnabledRequest(1L,true)),"enable","request");
        var identity=poll(s,challenge).identity();assertEquals(START+86400000,identity.expiresAtUnixMs());var peer=certificate(identity);
        var report=new ClientReport("managed","0.6.0-dev",0L,List.of());s.service.checkIn(peer,codec.json(new EndpointCheckIn(1L,report,null,null)),"first");
        var suspendBody=codec.json(new EndpointApprovalRequest(1L,false,null,null));
        var suspended=s.service.approval(s.admin,"owner","managed",suspendBody,"deapprove","request");assertEquals(suspended,s.service.approval(s.admin,"owner","managed",suspendBody,"deapprove","retry"));
        var suspendedDevice=codec.model(suspended.body(),EndpointDeviceRecord.class);assertFalse(suspendedDevice.connectionApproved());assertEquals("owner",suspendedDevice.userId());
        assertEquals(423,assertThrows(Failure.class,()->s.service.verifySocketPeer(peer)).status());
        assertEquals(423,assertThrows(Failure.class,()->s.service.checkIn(peer,codec.json(new EndpointCheckIn(1L,report,null,null)),"blocked-replay")).status());
        assertEquals(409,assertThrows(Failure.class,()->s.service.approval(s.admin,"owner","managed",codec.json(new EndpointApprovalRequest(1L,true,null,true)),"stale","request")).status());
        s.service.approval(s.admin,"owner","managed",codec.json(new EndpointApprovalRequest(2L,true,null,true)),"reapprove","request");
        s.clock.time+=500;s.service.checkIn(peer,codec.json(new EndpointCheckIn(2L,report,null,null)),"resumed");
        var row=codec.model(s.service.device(s.admin,"managed").body(),EndpointDeviceRecord.class);assertEquals(3L,row.approvalRevision());assertNull(row.connectionExpiresAtUnixMs());
        s.service.enabled(s.admin,"owner","managed",codec.json(new EndpointEnabledRequest(2L,false)),"disable","request");
        assertEquals(423,assertThrows(Failure.class,()->s.service.verifySocketPeer(peer)).status());
        var listed=codec.model(s.service.devices(s.admin).body(),EndpointManagedDevicePage.class).items().getFirst();assertFalse(listed.directoryDevice().enabled());assertTrue(listed.endpointDevice().connectionApproved());
        s.service.enabled(s.admin,"owner","managed",codec.json(new EndpointEnabledRequest(3L,true)),"enable-again","request");
        s.clock.time+=500;s.service.checkIn(peer,codec.json(new EndpointCheckIn(3L,report,null,null)),"enabled-again");
        s.clock.time=START+172800000;
        String registeredCsr=s.store.transaction(s.admin.tenant(),false,tx->tx.endpoint("managed").csr());
        var recovery=codec.model(s.service.start(codec.json(new EndpointEnrollmentStart("managed","0.6.0-dev",ClientPlatform.LINUX,registeredCsr,List.of())),"recover").body(),EndpointEnrollmentChallenge.class);
        assertEquals(EnrollmentState.APPROVED,review(s,recovery).state());var recovered=poll(s,recovery).identity();assertEquals("owner",recovered.userId());assertEquals(s.clock.time+86400000,recovered.expiresAtUnixMs());
        assertEquals(409,assertThrows(Failure.class,()->start(s,"managed")).status());
        var replay=codec.model(s.service.checkIn(certificate(recovered),codec.json(new EndpointCheckIn(3L,report,null,null)),"recovered-lost-response").body(),EndpointCheckInAck.class);
        assertEquals(s.clock.time,replay.serverTimeUnixMs());assertNull(replay.identity());
        s.service.checkIn(certificate(recovered),codec.json(new EndpointCheckIn(4L,report,null,null)),"recovered-check-in");
        row=codec.model(s.service.device(s.admin,"managed").body(),EndpointDeviceRecord.class);
        s.service.revoke(s.admin,"managed",codec.json(new EndpointRevokeRequest(row.revision())),"permanent","request");
        assertEquals(409,assertThrows(Failure.class,()->s.service.approval(s.admin,"owner","managed",codec.json(new EndpointApprovalRequest(4L,true,null,true)),"revoked-reapprove","request")).status());
        assertEquals(403,assertThrows(Failure.class,()->s.service.start(codec.json(new EndpointEnrollmentStart("managed","0.6.0-dev",ClientPlatform.LINUX,registeredCsr,List.of())),"revoked-recover")).status());
    }
}
