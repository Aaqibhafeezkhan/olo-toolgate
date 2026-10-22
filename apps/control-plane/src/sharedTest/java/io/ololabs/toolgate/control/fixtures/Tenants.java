// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.fixtures;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.sql.*;
import javax.sql.DataSource;

/** Explicit test-only provisioning. Production never derives UUIDs from aliases or creates tenants on writes. */
public final class Tenants {
    private static final io.ololabs.toolgate.control.application.TenantVaultKeys KEYS=new io.ololabs.toolgate.control.application.TenantVaultKeys(
        new io.ololabs.toolgate.control.adapter.ContractCodec(),new io.ololabs.toolgate.control.application.LocalVaultWrappingProvider("fixture",java.util.Map.of("fixture",new byte[32])));
    private Tenants() {}
    public static TenantId id(String label) {
        if (label.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) return new TenantId(label);
        return new TenantId(java.util.UUID.nameUUIDFromBytes(("test-tenant:" + label).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    }
    public static <T extends DataSource> T database(T source) {
        try (var probe = source.getConnection()) { provision(probe); return source; }
        catch (SQLException failure) { throw new IllegalStateException("Explicit tenant fixture provisioning failed", failure); }
    }
    public static void provision(Connection probe) throws SQLException {
        boolean local = probe.getMetaData().getDatabaseProductName().equals("SQLite");
        try (var c = local ? DriverManager.getConnection(probe.getMetaData().getURL()) : DriverManager.getConnection(
                probe.getMetaData().getURL(), "control_provisioner", System.getenv("CONTROL_TEST_PASSWORD"))) {
            c.setAutoCommit(false);
            for (String label : new String[]{"tenant","enterprise","endpoint","quickstart","upgrade","fresh","initial","rollback","sdk-builder","tenant-a","tenant-b","a","b","rls-a","rls-b","other","benchmark","bundle-tenant","fleet","invariant","permissions"}) {
                var tenant = id(label);
                if (!local) try (var s = c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) { s.setString(1,tenant.value()); s.execute(); }
                try (var s = c.prepareStatement("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES(?,?,?,'PROVISIONING',1,1) ON CONFLICT DO NOTHING")) {
                    s.setString(1,tenant.value()); s.setString(2,label); s.setString(3,label); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO control_tenant_authority(tenant_id) VALUES(?) ON CONFLICT DO NOTHING")) { s.setString(1,tenant.value()); s.executeUpdate(); }
                boolean keyMissing;
                try(var s=c.prepareStatement("SELECT 1 FROM control_tenant_vault_keys WHERE tenant_id=?")){s.setString(1,tenant.value());try(var r=s.executeQuery()){keyMissing=!r.next();}}
                if(keyMissing){var key=KEYS.initial(tenant);try(var s=c.prepareStatement("INSERT INTO control_tenant_vault_keys VALUES(?,1,'ACTIVE',?,?,?) ON CONFLICT DO NOTHING")){
                    s.setString(1,tenant.value());s.setString(2,key.providerReference());s.setString(3,key.wrappedEncryptionKey());s.setString(4,key.wrappedRetryKey());s.executeUpdate();
                }}
                try (var s = c.prepareStatement("UPDATE control_tenants SET status='ACTIVE' WHERE tenant_id=? AND status='PROVISIONING'")) { s.setString(1,tenant.value()); s.executeUpdate(); }
            }
            c.commit();
        }
    }
}
