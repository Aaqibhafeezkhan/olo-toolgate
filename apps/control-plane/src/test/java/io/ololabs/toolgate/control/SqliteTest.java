// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class SqliteTest {
    @TempDir Path directory;
    @Test void concurrentDirectoryReadsDoNotReserveTheWriterAndKeepSnapshotIsolation() {
        var codec=new ContractCodec();var store=new PostgresStore(SqliteState.open(directory.resolve("readers.sqlite")),codec);
        var actor=TestSupport.seed(store,codec,new Ids.TenantId("tenant"),"root");
        var service=new DirectoryService(store,codec,512,1048576);
        store.transaction(actor.tenant(),false,first->{
            long revision=first.load().revision();
            String audit=first.auditPage(0,20);
            long concurrentRevision=store.transaction(actor.tenant(),false,second->second.load().revision());
            assertEquals(revision,concurrentRevision);
            // A writer can commit while this reader retains its original snapshot.
            assertEquals(201,service.mutate(actor,Ids.Kind.USER,"concurrent-user","CREATE",
                DomainTest.user("concurrent-user",1),0,"concurrent-user-key","request").status());
            assertEquals(revision,first.load().revision());
            assertEquals(audit,first.auditPage(0,20));
            return null;
        });
        assertEquals(200,service.get(actor,Ids.Kind.USER,"concurrent-user").status());
    }
    @Test void readTransactionsCannotWriteAuditRecords() {
        var codec=new ContractCodec();var store=new PostgresStore(SqliteState.open(directory.resolve("readonly.sqlite")),codec);
        var actor=TestSupport.seed(store,codec,new Ids.TenantId("tenant"),"root");
        assertThrows(Failure.class,()->store.transaction(actor.tenant(),false,tx->{
            tx.audit(actor.id(),"CREATE","forbidden",1,"readonly","b".repeat(64));return null;
        }));
        assertFalse(store.transaction(actor.tenant(),false,tx->tx.auditPage(0,20)).contains("forbidden"));
    }
    @Test void defaultMembershipIsAtomicAndNeverGrantsRuntimeAccess() {
        var codec=new ContractCodec();var store=new PostgresStore(SqliteState.open(directory.resolve("membership.sqlite")),codec);
        var actor=TestSupport.seed(store,codec,new Ids.TenantId("tenant"),"root");var service=new DirectoryService(store,codec,512,1048576);
        var body=DomainTest.user("new-user",1).replace("true","false");
        var created=service.mutate(actor,Ids.Kind.USER,"new-user","CREATE",body,0,"new-user-key","request");
        assertEquals(created,service.mutate(actor,Ids.Kind.USER,"new-user","CREATE",body,0,"new-user-key","retry"));
        var groups=codec.model(service.memberships(actor,Ids.Kind.USER,"new-user").body(),io.ololabs.toolgate.contracts.GroupMembership.class);
        assertEquals(java.util.List.of("team-default"),groups.groupIds());
        assertFalse(codec.model(created.body(),io.ololabs.toolgate.contracts.ControlUser.class).enabled());
        boolean noGrants=store.transaction(actor.tenant(),false,tx->tx.load().entries().values().stream().noneMatch(e->e.id().kind()==Ids.Kind.GRANT));assertTrue(noGrants);
    }
    @Test void realTransactionsAuditReplayRestartAndImmutableTriggers() throws Exception {
        Path path=directory.resolve("control.sqlite"); var source=SqliteState.open(path); var codec=new ContractCodec();
        var store=new PostgresStore(source,codec);var service=new DirectoryService(store,codec,512,1048576);
        var actor=TestSupport.seed(store,codec,new Ids.TenantId("quickstart"),"root");
        var body="{\"id\":\"admin\",\"name\":\"Administrator\",\"enabled\":true,\"revision\":1}";
        var created=service.mutate(actor,Ids.Kind.USER,"admin","CREATE",body,0,"seed-user","req-create");assertEquals(201,created.status());
        assertEquals(created,service.mutate(actor,Ids.Kind.USER,"admin","CREATE",body,0,"seed-user","req-repeat"));
        var restarted=new PostgresStore(SqliteState.open(path),codec);
        assertEquals("Administrator",codec.model(new DirectoryService(restarted,codec,512,1048576).get(actor,Ids.Kind.USER,"admin").body(),io.ololabs.toolgate.contracts.ControlUser.class).name());
        assertTrue(restarted.transaction(actor.tenant(),false,tx->tx.auditPage(0,20)).contains("CREATE"));
        assertThrows(Failure.class,()->store.transaction(actor.tenant(),true,tx->{tx.audit(actor.id(),"CREATE","failed",1,"rollback","b".repeat(64));throw Failure.validation();}));
        try(var c=source.getConnection();var s=c.createStatement()){
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE control_audit SET target='tampered'"));
            s.execute("INSERT INTO control_fleet VALUES('quickstart','RELEASE','test',1,'{}')");
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE control_fleet SET revision=2 WHERE record_id='test'"));
            try(var r=s.executeQuery("SELECT count(*) FROM control_audit")){assertTrue(r.next());assertEquals(3,r.getInt(1));}
        }
    }
    @Test void cleanOrderedUpgradeAndFutureVersionFailClosed() throws Exception {
        var path=directory.resolve("upgrade.sqlite");var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+path);
        try(var c=source.getConnection();var statement=c.createStatement()) {
            statement.execute("CREATE TABLE quickstart_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            byte[] bytes;try(var input=SqliteTest.class.getResourceAsStream("/db/quickstart/V1.sql")){bytes=new String(java.util.Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);}
            var script=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            for(var sql:script.split("(?m)^-- statement\\s*$"))if(!sql.isBlank())statement.execute(sql);
            statement.execute("INSERT INTO quickstart_migrations VALUES(1,'"+DirectoryService.digest(bytes)+"')");
            statement.execute("INSERT INTO control_records VALUES('tenant','USER','kept',1,'{\"id\":\"kept\",\"name\":\"Kept\",\"enabled\":true,\"revision\":1}')");
        }
        var upgraded=SqliteState.open(path);
        try(var c=upgraded.getConnection();var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT count(*) FROM quickstart_migrations")){assertTrue(r.next());assertEquals(14,r.getInt(1));}
            try(var r=s.executeQuery("SELECT document FROM control_records WHERE record_id='kept'")){assertTrue(r.next());assertTrue(r.getString(1).contains("Kept"));}
            s.execute("INSERT INTO quickstart_migrations VALUES(15,'future')");
        }
        assertThrows(IllegalStateException.class,()->SqliteState.open(path));
    }
    @Test void appliedMigrationChecksumTamperingFailsClosed() throws Exception {
        var path=directory.resolve("control.sqlite");var source=SqliteState.open(path);
        try(var c=source.getConnection();var s=c.createStatement()){
            s.execute("UPDATE quickstart_migrations SET checksum='tampered' WHERE version=1");
        }
        assertThrows(IllegalStateException.class,()->SqliteState.open(path));
    }
}
