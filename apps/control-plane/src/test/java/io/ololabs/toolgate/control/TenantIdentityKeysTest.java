// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TenantIdentityKeysTest {
    @TempDir Path temp;
    private final ContractCodec codec=new ContractCodec();
    private record Fixture(javax.sql.DataSource runtime,javax.sql.DataSource platform,PostgresStore store,TenantRegistry registry){}
    private Fixture fixture(boolean postgres)throws Exception{
        javax.sql.DataSource runtime,platform;
        if(postgres){
            var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));var name="identity_"+UUID.randomUUID().toString().replace("-","");
            try(var c=DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+name);}
            var url=base.replace("/control?","/"+name+"?");org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var app=new org.postgresql.ds.PGSimpleDataSource();app.setURL(url);app.setUser("control_app");app.setPassword(password);runtime=app;
            var operator=new org.postgresql.ds.PGSimpleDataSource();operator.setURL(url);operator.setUser("control_provisioner");operator.setPassword(password);platform=operator;
        }else{runtime=SqliteState.open(temp.resolve(UUID.randomUUID()+".sqlite"));platform=runtime;}
        return new Fixture(runtime,platform,new PostgresStore(runtime,codec),new TenantRegistry(platform,codec));
    }
    private TenantIdentityFixtures tenant(Fixture f,String alias)throws Exception{
        var tenant=f.registry.allocate(alias,alias);String origin="https://"+alias+".example.test";
        var profile=new Store.RuntimeProfile(tenant.id().value(),alias,origin,origin);
        f.registry.initialize(tenant.id(),"a".repeat(64),"[]",List.of(alias+".example.test"),profile,unit->{TestSupport.seed(unit,codec,tenant.id(),"admin");unit.transaction(tenant.id(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});});
        return new TenantIdentityFixtures(codec,tenant.id(),profile);
    }
    private List<Store.IdentityKey> keys(Fixture f,Ids.TenantId tenant){return f.store.transaction(tenant,false,Store.Session::identityKeys);}
    private RsaTenantDiscovery.Verified document(Fixture f,TenantIdentityFixtures tenant,RsaTenantDiscovery.Trust pin){
        long now=System.currentTimeMillis();var bound=tenant.custody.bind(tenant.tenant,tenant.profile,keys(f,tenant.tenant),now);
        return RsaTenantDiscovery.verify(codec,bound.discovery(codec,tenant.tenant,tenant.profile,"0.0.0",now),tenant.profile.controlUrl(),tenant.tenant,pin,now);
    }
    @Test void sqliteCustodyIsolationRotationAndRollback()throws Exception{isolation(fixture(false));}
    @Test void sqliteStaleEndpointCannotSignAfterKeyPublication()throws Exception{staleEndpoint(fixture(false));}
    @Test void postgresStaleEndpointCannotSignAfterKeyPublication()throws Exception{staleEndpoint(fixture(true));}
    private void staleEndpoint(Fixture f)throws Exception{
        var tenant=tenant(f,"snapshot-tenant");
        f.registry.installIdentity(tenant.tenant,tenant.custody,1,tenant.references,"first");
        long now=System.currentTimeMillis();var bound=tenant.custody.bind(tenant.tenant,tenant.profile,keys(f,tenant.tenant),now);
        var endpoint=new EndpointService(f.store,codec,bound.issuer(),java.time.Clock.systemUTC(),true,tenant.tenant.value(),tenant.profile.serverId(),tenant.profile.organization(),tenant.profile.controlUrl(),tenant.profile.gatewayUrl())
            .identityGeneration(bound.generation()).discoverySigner((minimum,time)->bound.discovery(codec,tenant.tenant,tenant.profile,minimum,time));
        assertEquals(200,endpoint.discovery().status());
        f.registry.installIdentity(tenant.tenant,tenant.custody,2,tenant.announced(),"announce");
        assertEquals(503,assertThrows(Failure.class,endpoint::discovery).status());
        var generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));var key=generator.generateKeyPair();
        var csr=new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(new org.bouncycastle.asn1.x500.X500Name("CN=device"),key.getPublic()).build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate()));
        var text=new java.io.StringWriter();try(var writer=new org.bouncycastle.openssl.jcajce.JcaPEMWriter(text)){writer.writeObject(csr);}
        var request=new io.ololabs.toolgate.contracts.EndpointEnrollmentStart("device","0.10.0-dev",io.ololabs.toolgate.contracts.ClientPlatform.LINUX,text.toString(),List.of("endpoint.identity.v1"));
        assertEquals(503,assertThrows(Failure.class,()->endpoint.start(codec.json(request),"stale-request")).status());
        long pending=f.store.transaction(tenant.tenant,false,tx->tx.pendingEnrollments(System.currentTimeMillis()));assertEquals(0L,pending);
    }
    @Test void postgresCustodyIsolationRotationAndRollback()throws Exception{isolation(fixture(true));}
    private void isolation(Fixture f)throws Exception{
        var a=tenant(f,"tenant-a");var b=tenant(f,"tenant-b");
        assertThrows(Failure.class,()->a.custody.bind(a.tenant,a.profile,keys(f,a.tenant),System.currentTimeMillis()));
        assertTrue(f.registry.installIdentity(a.tenant,a.custody,1,a.references,"publish-a"));
        assertFalse(f.registry.installIdentity(a.tenant,a.custody,1,a.references,"publish-a-again"));
        assertTrue(f.registry.installIdentity(b.tenant,b.custody,1,b.references,"publish-b"));
        var other=keys(f,b.tenant);var initial=document(f,a,null);long epoch=f.store.transaction(a.tenant,false,tx->tx.enterprise().authorizationEpoch());
        assertTrue(keys(f,a.tenant).stream().allMatch(row->!row.publicMaterial().contains("PRIVATE KEY")));
        assertThrows(Failure.class,()->a.custody.bind(b.tenant,b.profile,keys(f,a.tenant),System.currentTimeMillis()));
        assertThrows(Failure.class,()->f.registry.installIdentity(a.tenant,a.custody,3,a.announced(),"skip-generation"));
        var dirty=a.references.stream().map(ref->ref.purpose().equals("DEVICE_CA")?new TenantIdentityCustody.Reference(ref.purpose(),ref.keyId(),ref.state(),ref.certificatePem()+"accidentally concatenated material"):ref).toList();
        assertThrows(Failure.class,()->f.registry.installIdentity(a.tenant,a.custody,2,dirty,"dirty-certificate"));
        // A completely unannounced signer cannot be activated in a single publication.
        assertThrows(Failure.class,()->f.registry.installIdentity(a.tenant,a.custody,2,a.retired(),"skip-announcement"));
        assertEquals(initial.trust().keys(),document(f,a,initial.trust()).trust().keys());
        assertTrue(f.registry.installIdentity(a.tenant,a.custody,2,a.announced(),"announce"));var announced=document(f,a,initial.trust());
        assertTrue(f.registry.installIdentity(a.tenant,a.custody,3,a.rotated(),"activate"));var rotated=document(f,a,announced.trust());
        assertTrue(f.registry.installIdentity(a.tenant,a.custody,4,a.retired(),"retire"));
        a.forget("DISCOVERY","discovery-a");var retired=document(f,a,rotated.trust());assertEquals(4,retired.trust().version());
        assertEquals(1,retired.trust().keys().keys().size());assertEquals(other,keys(f,b.tenant));
        assertTrue(f.store.transaction(a.tenant,false,tx->tx.enterprise().authorizationEpoch())>epoch);
        assertThrows(Failure.class,()->f.registry.installIdentity(a.tenant,a.custody,3,a.rotated(),"rollback"));
        assertEquals(4,document(f,a,retired.trust()).trust().version());
        long now=System.currentTimeMillis();
        var generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));var device=generator.generateKeyPair();
        var csr=new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(new org.bouncycastle.asn1.x500.X500Name("CN=device-test"),device.getPublic()).build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(device.getPrivate()));
        var text=new java.io.StringWriter();try(var writer=new org.bouncycastle.openssl.jcajce.JcaPEMWriter(text)){writer.writeObject(csr);}
        var issuer=a.custody.bind(a.tenant,a.profile,keys(f,a.tenant),now).issuer();
        var identity=issuer.issue(text.toString(),"device-test",a.tenant.value(),"admin",a.profile.serverId(),now);
        assertThrows(Failure.class,()->issuer.issue(text.toString(),"device-test",b.tenant.value(),"admin",a.profile.serverId(),now));
        assertThrows(Failure.class,()->issuer.discovery("unused"),"The tenant device CA cannot sign discovery");
        var oldCa=a.references.stream().filter(ref->ref.purpose().equals("DEVICE_CA")).findFirst().orElseThrow();var newCa=a.addAuthority();
        var overlap=new ArrayList<>(a.retired().stream().filter(ref->ref.purpose().equals("DISCOVERY")).toList());
        overlap.add(new TenantIdentityCustody.Reference(oldCa.purpose(),oldCa.keyId(),"READ_ONLY",oldCa.certificatePem()));overlap.add(newCa);
        assertTrue(f.registry.installIdentity(a.tenant,a.custody,5,overlap,"rotate-ca"));
        var caOverlap=a.custody.bind(a.tenant,a.profile,keys(f,a.tenant),now).issuer();
        var parser=java.security.cert.CertificateFactory.getInstance("X.509");
        var oldLeaf=(java.security.cert.X509Certificate)parser.generateCertificate(new java.io.ByteArrayInputStream(identity.certificatePem().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(a.tenant,caOverlap.verifyPeer(oldLeaf,now).tenant(),"Read-only CA continues to verify existing devices");
        var nextIdentity=caOverlap.issue(text.toString(),"device-test",a.tenant.value(),"admin",a.profile.serverId(),now);
        assertNotEquals(identity.issuerCertificatePem(),nextIdentity.issuerCertificatePem());
        var finalRing=overlap.stream().filter(ref->!ref.keyId().equals(oldCa.keyId())).toList();
        assertTrue(f.registry.installIdentity(a.tenant,a.custody,6,finalRing,"retire-ca"));a.forget(oldCa.purpose(),oldCa.keyId());
        var finalIssuer=a.custody.bind(a.tenant,a.profile,keys(f,a.tenant),now).issuer();
        assertThrows(Failure.class,()->finalIssuer.verifyPeer(oldLeaf,now));
        var newLeaf=(java.security.cert.X509Certificate)parser.generateCertificate(new java.io.ByteArrayInputStream(nextIdentity.certificatePem().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(a.tenant,finalIssuer.verifyPeer(newLeaf,now).tenant());assertEquals(other,keys(f,b.tenant));
        if(f.runtime!=f.platform)try(var c=f.runtime.getConnection();var s=c.createStatement()){
            assertEquals("42501",assertThrows(SQLException.class,()->s.execute("UPDATE control_tenant_identity_keys SET state='RETIRED'")).getSQLState());
            assertEquals("42501",assertThrows(SQLException.class,()->s.execute("DELETE FROM control_tenant_identity_keys")).getSQLState());
        }
        var c=tenant(f,"tenant-c");c.reuseDiscoveryFrom(b);
        assertThrows(Failure.class,()->f.registry.installIdentity(c.tenant,c.custody,1,c.references,"shared-key"));
        assertTrue(keys(f,c.tenant).isEmpty(),"A global duplicate key rolls back the entire publication");
        assertEquals(other,keys(f,b.tenant));
    }
}
