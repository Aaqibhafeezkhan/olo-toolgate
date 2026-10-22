// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.SqliteState;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class UnsupportedDatabaseTest {
    @TempDir Path temp;
    @Test void sqliteRejectsUnmanagedStateAndChangedChecksumsWithoutRepairingData() throws Exception {
        var file=temp.resolve("unmanaged.sqlite");String url="jdbc:sqlite:"+file;
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){
            s.execute("CREATE TABLE existing_state(payload TEXT NOT NULL)");s.execute("INSERT INTO existing_state VALUES('must-preserve')");
        }
        assertThrows(IllegalStateException.class,()->SqliteState.open(file));
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT payload FROM existing_state")){assertTrue(r.next());assertEquals("must-preserve",r.getString(1));}
            try(var r=s.executeQuery("SELECT name FROM sqlite_master WHERE name='quickstart_migrations'")){assertFalse(r.next());}
        }
        var current=temp.resolve("checksum.sqlite");var source=SqliteState.open(current);
        try(var c=source.getConnection();var s=c.createStatement()){s.execute("UPDATE quickstart_migrations SET checksum='unsupported'");}
        assertThrows(IllegalStateException.class,()->SqliteState.open(current));
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT checksum FROM quickstart_migrations")){assertTrue(r.next());assertEquals("unsupported",r.getString(1));}
    }
    @Test void postgresRejectsNonEmptyUnmanagedStateWithoutBaseliningOrDeletingIt() throws Exception {
        var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
        String name="unmanaged_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+name);}
        String url=base.replace("/control?","/"+name+"?");
        try(var c=DriverManager.getConnection(url,"control_migrator",password);var s=c.createStatement()){
            s.execute("CREATE TABLE existing_state(payload TEXT NOT NULL)");s.execute("INSERT INTO existing_state VALUES('must-preserve')");
        }
        assertThrows(org.flywaydb.core.api.FlywayException.class,()->org.flywaydb.core.Flyway.configure()
            .dataSource(url,"control_migrator",password).baselineOnMigrate(false).cleanDisabled(true).load().migrate());
        try(var c=DriverManager.getConnection(url,"control_migrator",password);var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT payload FROM existing_state")){assertTrue(r.next());assertEquals("must-preserve",r.getString(1));}
            try(var r=s.executeQuery("SELECT to_regclass('flyway_schema_history')")){assertTrue(r.next());assertNull(r.getString(1));}
        }
    }
}
