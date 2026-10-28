// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.EnterpriseVaultWrite;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TenantVaultTest {
    @TempDir Path temp;
    private final ContractCodec codec=new ContractCodec();
    private record Fixture(javax.sql.DataSource runtime,javax.sql.DataSource platform,PostgresStore store,TenantRegistry registry,TenantVaultKeys keys,VaultService vault) {}
    private Fixture fixture(boolean postgres) throws Exception {
        javax.sql.DataSource runtime,platform;
        if(postgres) {
            var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=System.getenv("CONTROL_TEST_PASSWORD");var name="vault_"+UUID.randomUUID().toString().replace("-","");
            try(var c=DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+name);}
            var url=base.replace("/control?","/"+name+"?");org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var app=new org.postgresql.ds.PGSimpleDataSource();app.setURL(url);app.setUser("control_app");app.setPassword(password);runtime=app;
            var operator=new org.postgresql.ds.PGSimpleDataSource();operator.setURL(url);operator.setUser("control_provisioner");operator.setPassword(password);platform=operator;
        }else{runtime=SqliteState.open(temp.resolve(UUID.randomUUID()+".sqlite"));platform=runtime;}
        var keys=new TenantVaultKeys(codec,new LocalVaultWrappingProvider("first",Map.of("first",new byte[32])));
        var store=new PostgresStore(runtime,codec);return new Fixture(runtime,platform,store,new TenantRegistry(platform,codec,keys),keys,new VaultService(store,codec,keys));
    }
    private DirectoryService.Actor tenant(Fixture f,String alias) {
        var row=f.registry.allocate(alias,alias);
        assertTrue(f.registry.initialize(row.id(),"a".repeat(64),"[]",unit->{
            TestSupport.seed(unit,codec,row.id(),"admin");unit.transaction(row.id(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});
        }));
        return new DirectoryService.Actor(row.id(),"a".repeat(64),true,false,"admin");
    }
    private String write(String value){return codec.json(new EnterpriseVaultWrite("provider/token",value,"default-tools","default-devices"));}
    private EnterpriseStore.Secret secret(Fixture f,Ids.TenantId tenant){return f.store.transaction(tenant,false,tx->tx.enterprise().secret("provider/token"));}
    private EnterpriseStore.VaultKey key(Fixture f,Ids.TenantId tenant){return f.store.transaction(tenant,false,tx->tx.enterprise().currentVaultKey());}
    private byte[] context(Ids.TenantId tenant,EnterpriseStore.Secret secret){return codec.json(List.of("ToolGate tenant secret",1L,tenant.value(),secret.name(),secret.version(),secret.toolGroupId(),secret.deviceGroupId(),secret.keyVersion())).getBytes(StandardCharsets.UTF_8);}
    private String clear(TenantVaultKeys keys,Ids.TenantId tenant,EnterpriseStore.VaultKey key,EnterpriseStore.Secret secret){byte[] raw=keys.encryption(tenant,key);try{return new String(VaultEncryption.decrypt(raw,secret.cipher(),context(tenant,secret)),StandardCharsets.UTF_8);}finally{Arrays.fill(raw,(byte)0);}}
    @Test void sqliteTenantCustodyRotationAndRetryIsolation() throws Exception {isolation(fixture(false));}
    @Test void postgresTenantCustodyRotationAndRetryIsolation() throws Exception {isolation(fixture(true));}
    private void isolation(Fixture f) throws Exception {
        var a=tenant(f,"tenant-a");var b=tenant(f,"tenant-b");
        var reply=f.vault.write(a,write("first tenant value"),"same-key");f.vault.write(b,write("second tenant value"),"same-key");
        var secretA=secret(f,a.tenant());var secretB=secret(f,b.tenant());var keyA=key(f,a.tenant());var keyB=key(f,b.tenant());
        assertNotEquals(keyA.wrappedEncryptionKey(),keyB.wrappedEncryptionKey());assertNotEquals(keyA.wrappedEncryptionKey(),keyA.wrappedRetryKey());
        assertEquals("first tenant value",clear(f.keys,a.tenant(),keyA,secretA));assertEquals("second tenant value",clear(f.keys,b.tenant(),keyB,secretB));
        assertThrows(Failure.class,()->clear(f.keys,b.tenant(),keyA,secretA));
        assertThrows(Failure.class,()->clear(f.keys,a.tenant(),keyA,new EnterpriseStore.Secret(secretA.name(),secretA.toolGroupId(),secretA.deviceGroupId(),2,1,secretA.cipher())));
        assertThrows(Failure.class,()->clear(f.keys,a.tenant(),keyA,new EnterpriseStore.Secret("different",secretA.toolGroupId(),secretA.deviceGroupId(),1,1,secretA.cipher())));
        assertThrows(Failure.class,()->clear(f.keys,a.tenant(),keyA,new EnterpriseStore.Secret(secretA.name(),"different",secretA.deviceGroupId(),1,1,secretA.cipher())));
        assertThrows(Failure.class,()->f.keys.encryption(a.tenant(),new EnterpriseStore.VaultKey(1,"ACTIVE",keyA.providerReference(),keyA.wrappedRetryKey(),keyA.wrappedEncryptionKey())));
        assertThrows(Failure.class,()->f.keys.encryption(a.tenant(),new EnterpriseStore.VaultKey(2,"ACTIVE",keyA.providerReference(),keyA.wrappedEncryptionKey(),keyA.wrappedRetryKey())));
        assertThrows(Failure.class,()->f.keys.encryption(a.tenant(),new EnterpriseStore.VaultKey(1,"ACTIVE","local:unknown",keyA.wrappedEncryptionKey(),keyA.wrappedRetryKey())));
        long epoch=f.store.transaction(a.tenant(),false,tx->tx.enterprise().authorizationEpoch());
        var rotated=f.registry.maintainVault(a.tenant(),"ROTATE",1,"rotate");
        assertEquals(rotated,f.registry.maintainVault(a.tenant(),"ROTATE",1,"rotate"));
        assertEquals(2,secret(f,a.tenant()).version());assertEquals(2,secret(f,a.tenant()).keyVersion());
        assertTrue(f.store.transaction(a.tenant(),false,tx->tx.enterprise().authorizationEpoch())>epoch);
        assertEquals("first tenant value",clear(f.keys,a.tenant(),key(f,a.tenant()),secret(f,a.tenant())));
        assertEquals(secretB,secret(f,b.tenant()));assertEquals(keyB,key(f,b.tenant()));
        assertEquals(reply,f.vault.write(a,write("first tenant value"),"same-key"),"Key rotation preserves the independent retry-authentication key");
        assertEquals(409,assertThrows(Failure.class,()->f.vault.write(a,write("changed"),"same-key")).status());
        byte[] nextKek=new byte[32];Arrays.fill(nextKek,(byte)7);
        var nextKeys=new TenantVaultKeys(codec,new LocalVaultWrappingProvider("next",Map.of("first",new byte[32],"next",nextKek)));
        var nextRegistry=new TenantRegistry(f.platform,codec,nextKeys);
        var before=secret(f,a.tenant());nextRegistry.maintainVault(a.tenant(),"REWRAP",2,"rewrap");assertEquals(before,secret(f,a.tenant()),"KEK rewrapping does not rewrite secrets");
        var onlyNext=new TenantVaultKeys(codec,new LocalVaultWrappingProvider("next",Map.of("next",nextKek)));
        assertEquals("first tenant value",clear(onlyNext,a.tenant(),key(f,a.tenant()),secret(f,a.tenant())));
        assertThrows(Failure.class,()->f.keys.encryption(a.tenant(),key(f,a.tenant())));
        nextRegistry.maintainVault(a.tenant(),"RETIRE",1,"retire");
        var retired=f.store.transaction(a.tenant(),false,tx->tx.enterprise().vaultKey(1));assertEquals("RETIRED",retired.state());assertThrows(Failure.class,()->onlyNext.encryption(a.tenant(),retired));
        assertEquals(409,assertThrows(Failure.class,()->nextRegistry.maintainVault(a.tenant(),"RETIRE",2,"retire-current")).status());
        if(f.runtime!=f.platform)try(var c=f.runtime.getConnection();var s=c.createStatement()){
            assertEquals("42501",assertThrows(SQLException.class,()->s.execute("UPDATE control_tenant_vault_keys SET state='RETIRED'")).getSQLState());
        }
    }
    @Test void unsupportedCiphertextRollsBackRotationAndNeverConvertsStoredData() throws Exception {
        var f=fixture(false);var actor=tenant(f,"tenant-a");f.vault.write(actor,write("value"),"write");
        var before=secret(f,actor.tenant());
        f.store.transaction(actor.tenant(),true,tx->{tx.enterprise().saveSecret(new EnterpriseStore.Secret(before.name(),before.toolGroupId(),before.deviceGroupId(),2,1,"pre-release-raw-ciphertext"));return null;});
        var bad=secret(f,actor.tenant());var key=key(f,actor.tenant());
        assertEquals(503,assertThrows(Failure.class,()->f.registry.maintainVault(actor.tenant(),"ROTATE",1,"rotate")).status());
        assertEquals(bad,secret(f,actor.tenant()));assertEquals(key,key(f,actor.tenant()));
        assertEquals(1,f.store.<Integer>transaction(actor.tenant(),false,tx->tx.enterprise().vaultKeys().size()).intValue());
    }
    @Test void failedInitializationRollsBackWrappedKeysInBothStorageModes() throws Exception {
        for(boolean postgres:List.of(false,true)) {
            var f=fixture(postgres);var tenant=f.registry.allocate("rollback","Rollback");
            assertThrows(IllegalStateException.class,()->f.registry.initialize(tenant.id(),"a".repeat(64),"[]",unit->{throw new IllegalStateException("abort");}));
            try(var c=f.platform.getConnection()) {
                if(postgres)TestSupport.scope(c,tenant.id());
                try(var s=c.prepareStatement("SELECT count(*) FROM control_tenant_vault_keys WHERE tenant_id=?")) {
                    s.setString(1,tenant.id().value());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
                }
                if(postgres)c.rollback();
            }
            assertEquals("PROVISIONING",f.registry.find("rollback").status());
        }
    }
    @Test void runtimeWriteCannotProvisionMissingTenantKeys() throws Exception {
        var f=fixture(false);var registry=new TenantRegistry(f.platform,codec);var row=registry.allocate("keyless","Keyless");
        registry.initialize(row.id(),"a".repeat(64),"[]",unit->{TestSupport.seed(unit,codec,row.id(),"admin");unit.transaction(row.id(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});});
        var actor=new DirectoryService.Actor(row.id(),"a".repeat(64),true,false,"admin");
        assertEquals(503,assertThrows(Failure.class,()->f.vault.write(actor,write("value"),"write")).status());
        f.store.transaction(row.id(),false,tx->{assertTrue(tx.enterprise().vaultKeys().isEmpty());assertNull(tx.enterprise().secret("provider/token"));return null;});
    }
}
