// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit single-node persistence adapter. IMMEDIATE transactions serialize writers;
 * immutable migrations and triggers retain the same audit/version boundary as PostgreSQL.
 * The supervisor owns the exclusive /data lock; this adapter is never an HA backend.
 */
public final class SqliteState {
    private SqliteState() { }
    public static javax.sql.DataSource open(Path path) {
        try {
            if (!path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isDirectory(path.getParent())) throw new IllegalArgumentException();
            var settings = new org.sqlite.SQLiteConfig();
            settings.enforceForeignKeys(true); settings.setBusyTimeout(3000);
            settings.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
            settings.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.FULL);
            settings.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
            var source = new org.sqlite.SQLiteDataSource(settings); source.setUrl("jdbc:sqlite:" + path);
            try (var c = source.getConnection(); var s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS quickstart_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
                try(var r=s.executeQuery("SELECT COALESCE(max(version),0) FROM quickstart_migrations")){if(!r.next() || r.getInt(1)>12)throw new IllegalStateException("Newer local state requires a newer image");}
                for(int version=1;version<=12;version++) {
                    String script;
                    try(var input=SqliteState.class.getResourceAsStream("/db/quickstart/V"+version+".sql")){
                        if(input==null)throw new IllegalStateException(); script=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
                    }
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    try(var query=c.prepareStatement("SELECT checksum FROM quickstart_migrations WHERE version=?")){
                        query.setInt(1,version);try(var row=query.executeQuery()){if(row.next()){if(!hash.equals(row.getString(1)))throw new IllegalStateException("Local migration checksum mismatch");continue;}}
                    }
                    c.setAutoCommit(false);
                    try {
                        // Each statement ends with a delimiter on its own line, including trigger bodies.
                        for(String statement:script.split("(?m)^-- statement\\s*$"))if(!statement.isBlank())s.execute(statement);
                        try(var insert=c.prepareStatement("INSERT INTO quickstart_migrations VALUES(?,?)")){insert.setInt(1,version);insert.setString(2,hash);insert.executeUpdate();}
                        c.commit();
                    } catch(Exception failure){c.rollback();throw failure;} finally{c.setAutoCommit(true);}
                }
            }
            return source;
        } catch(Exception failure){throw new IllegalStateException("Quickstart local state validation/migration failed",failure);}
    }
    static boolean local(Connection c) throws SQLException {return c.getMetaData().getDatabaseProductName().equals("SQLite");}
    static String sql(Connection c,String sql) throws SQLException {
        if(!local(c))return sql;
        return sql
            .replace("(document::jsonb->>'expiresAtUnixMs')::bigint", "json_extract(document,'$.expiresAtUnixMs')")
            .replace("::jsonb", "").replace("::text", "").replace("GREATEST(", "max(")
            .replace("clock_timestamp()", "unixepoch()");
    }
}
