// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Platform-only provisioning/lifecycle. No registry methods are exposed on the tenant Store port. */
@ApplicationScoped
public class TenantRegistry {
    public record Tenant(TenantId id, String alias, String name, String status) {}
    public record InitializationFailure(String code,long failedAt) {}
    private final javax.sql.DataSource source;
    private final PlatformDatabase database;
    private final ContractCodec codec;
    private final VaultKeyBootstrap keyBootstrap;
    private final TenantVaultKeys configuredKeys;
    @Inject public TenantRegistry(PlatformDatabase database, ContractCodec codec,VaultKeyBootstrap keys) { this.database = database; this.codec = codec; this.source = null;this.keyBootstrap=keys;this.configuredKeys=null; }
    public TenantRegistry(javax.sql.DataSource source, ContractCodec codec) { this(source,codec,null); }
    public TenantRegistry(javax.sql.DataSource source,ContractCodec codec,TenantVaultKeys keys){this.source=source;this.codec=codec;this.database=null;this.keyBootstrap=null;this.configuredKeys=keys;}
    private TenantVaultKeys keys(){return keyBootstrap==null?configuredKeys:keyBootstrap.optional();}
    private javax.sql.DataSource source() { return source == null ? database.source() : source; }
    private PostgresStore platformStore() { return new PostgresStore(source(), codec); }
    public static String alias(String alias) {
        if (alias == null || !alias.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("Invalid tenant alias");
        return alias;
    }
    public Tenant find(String alias) {
        alias(alias);
        try (var c = source().getConnection(); var s = c.prepareStatement("SELECT tenant_id,alias,display_name,status FROM control_tenants WHERE alias=?")) {
            s.setString(1, alias); try (var r = s.executeQuery()) { return r.next() ? tenant(r) : null; }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public List<TenantId> activeAfter(String cursor, int limit) {
        if (limit < 1 || limit > 256) throw Failure.validation();
        if (!cursor.isEmpty()) new TenantId(cursor);
        try (var c = source().getConnection(); var s = c.prepareStatement("SELECT tenant_id FROM control_tenants WHERE status='ACTIVE' AND tenant_id>? ORDER BY tenant_id LIMIT ?")) {
            s.setString(1, cursor); s.setInt(2, limit); try (var r = s.executeQuery()) {
                var result = new ArrayList<TenantId>(); while (r.next()) result.add(new TenantId(r.getString(1))); return List.copyOf(result);
            }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    private Tenant tenant(ResultSet r) throws SQLException { return new Tenant(new TenantId(r.getString(1)), r.getString(2), r.getString(3), r.getString(4)); }
    public Tenant allocate(String alias, String name) { return allocate(alias, name, null); }
    /** A local composition's already generated UUID is accepted only at this protected bootstrap boundary. */
    Tenant allocate(String alias, String name, TenantId bootstrapId) {
        alias(alias); if (name == null || name.isBlank() || name.length() > 256) throw Failure.validation();
        var existing = find(alias);
        if (existing != null) {
            if (bootstrapId != null && !existing.id().equals(bootstrapId)) throw Failure.conflict();
            return existing;
        }
        var id = bootstrapId == null ? TenantId.create() : bootstrapId;
        try {
            return platformStore().platformTransaction(id, (c, tx) -> {
                try {
                long now = System.currentTimeMillis();
                try (var s = c.prepareStatement("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES(?,?,?,'PROVISIONING',?,?)")) {
                    s.setString(1, id.value()); s.setString(2, alias); s.setString(3, name); s.setLong(4, now); s.setLong(5, now); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO control_tenant_authority(tenant_id) VALUES(?)")) { s.setString(1, id.value()); s.executeUpdate(); }
                return new Tenant(id, alias, name, "PROVISIONING");
                } catch (SQLException failure) { throw Failure.unavailable(); }
            });
        } catch (Failure race) {
            existing = find(alias);
            if (existing != null && (bootstrapId == null || existing.id().equals(bootstrapId))) return existing;
            throw race;
        }
    }
    public boolean initialize(TenantId tenant, String digest, String provenance, Consumer<Store> initializer) {
        return initialize(tenant,digest,provenance,List.of(),initializer);
    }
    public boolean initialize(TenantId tenant, String digest, String provenance, List<String> hosts, Consumer<Store> initializer) {
        if (digest == null || !digest.matches("[a-f0-9]{64}") || provenance == null || provenance.length() > 65536) throw Failure.validation();
        try { return initializeAtomically(tenant,digest,provenance,hosts,initializer); }
        catch(RuntimeException failure) {
            // A separate commit preserves a bounded, non-sensitive diagnostic after the seed rolls back.
            try { recordInitializationFailure(tenant); } catch(RuntimeException diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        }
    }
    public InitializationFailure initializationFailure(TenantId tenant) {
        try(var c=source().getConnection();var s=c.prepareStatement("SELECT initialization_failure_code,initialization_failed_at FROM control_tenants WHERE tenant_id=?")) {
            s.setString(1,tenant.value());try(var r=s.executeQuery()) {
                if(!r.next())throw Failure.tenantUnavailable();
                return r.getString(1)==null?null:new InitializationFailure(r.getString(1),r.getLong(2));
            }
        } catch(SQLException failure){throw Failure.unavailable();}
    }
    private void recordInitializationFailure(TenantId tenant) {
        try(var c=source().getConnection();var s=c.prepareStatement("UPDATE control_tenants SET initialization_failure_code='INITIALIZATION_FAILED',initialization_failed_at=?,updated_at=? WHERE tenant_id=? AND status='PROVISIONING'")) {
            long now=System.currentTimeMillis();s.setLong(1,now);s.setLong(2,now);s.setString(3,tenant.value());s.executeUpdate();
        } catch(SQLException failure){throw Failure.unavailable();}
    }
    private boolean initializeAtomically(TenantId tenant,String digest,String provenance,List<String> hosts,Consumer<Store> initializer) {
        return platformStore().platformTransaction(tenant, (c, tx) -> {
            try {
                String status;
                try (var s = c.prepareStatement("SELECT status FROM control_tenants WHERE tenant_id=?" + (SqliteState.local(c) ? "" : " FOR UPDATE"))) {
                    s.setString(1, tenant.value()); try (var r = s.executeQuery()) { if (!r.next()) throw Failure.tenantUnavailable(); status = r.getString(1); }
                }
                try (var s = c.prepareStatement("SELECT 1 FROM control_tenant_initializations WHERE tenant_id=?")) {
                    s.setString(1, tenant.value()); try (var r = s.executeQuery()) { if (r.next()) return false; }
                }
                if (!status.equals("PROVISIONING")) throw Failure.conflict();
                var vaultKeys=keys();if(vaultKeys!=null)vaultKeys.provision(tenant,tx);
                for(var host:hosts)try(var s=c.prepareStatement("INSERT INTO control_tenant_hosts(hostname,tenant_id) VALUES(?,?)")){
                    s.setString(1,TenantHosts.normalize(host));s.setString(2,tenant.value());s.executeUpdate();
                }
                // Existing application services join one commit boundary; no HTTP caller can choose this Store.
                Store unit = new Store() {
                    public <T> T transaction(TenantId requested, boolean write, Function<Session, T> work) {
                        if (!tenant.equals(requested)) throw Failure.validation(); return work.apply(tx);
                    }
                };
                initializer.accept(unit);
                var graph = tx.load(); graph.validate(512, 1048576); codec.validatePolicies(graph);
                var admin = tx.bootstrapAdminUserId();
                if (admin == null || !new ManagementAccess(codec).superAdministrator(graph, admin)) throw ManagementAccess.denied();
                long now = System.currentTimeMillis();
                try (var s = c.prepareStatement("INSERT INTO control_tenant_initializations(tenant_id,version,effective_digest,provenance,completed_at) VALUES(?,1,?,?,?)")) {
                    s.setString(1, tenant.value()); s.setString(2, digest); s.setString(3, provenance); s.setLong(4, now); s.executeUpdate();
                }
                tx.audit(DirectoryService.digest("tenant-provisioning"), "TENANT_INITIALIZE", "tenant", graph.revision(), "initialize-" + digest.substring(0, 32), digest);
                try (var s = c.prepareStatement("UPDATE control_tenants SET status='ACTIVE',initialization_failure_code=NULL,initialization_failed_at=NULL,updated_at=? WHERE tenant_id=? AND status='PROVISIONING'")) {
                    s.setLong(1, now); s.setString(2, tenant.value()); if (s.executeUpdate() != 1) throw Failure.conflict();
                }
                return true;
            } catch (SQLException failure) { throw Failure.unavailable(); }
        });
    }
    public boolean initialized(TenantId tenant){
        return platformStore().platformTransaction(tenant,(c,tx)->{
            try(var s=c.prepareStatement("SELECT 1 FROM control_tenant_initializations WHERE tenant_id=?")){s.setString(1,tenant.value());try(var r=s.executeQuery()){return r.next();}}
            catch(SQLException failure){throw Failure.unavailable();}
        });
    }
    /** Protected operator boundary. The registry lock serializes rotation against all admitted tenant writes. */
    public Store.Reply maintainVault(TenantId tenant,String operation,long version,String requestId) {
        if(!Set.of("ROTATE","REWRAP","RETIRE").contains(operation)||version<1)throw Failure.validation();
        io.ololabs.toolgate.control.domain.Ids.valid(requestId);var keys=keys();if(keys==null)throw Failure.unavailable();
        String actor=DirectoryService.digest("platform-vault"),digest=DirectoryService.digest(codec.json(List.of(tenant.value(),operation,version)));
        return platformStore().platformTransaction(tenant,(c,tx)->{
            try(var s=c.prepareStatement("SELECT status FROM control_tenants WHERE tenant_id=?"+(SqliteState.local(c)?"":" FOR UPDATE"))) {
                s.setString(1,tenant.value());try(var r=s.executeQuery()){if(!r.next()||!r.getString(1).equals("ACTIVE"))throw Failure.tenantUnavailable();}
            }catch(SQLException failure){throw Failure.unavailable();}
            var replay=tx.replay(actor,requestId,digest);if(replay!=null)return replay;
            var current=keys.current(tx);
            if(operation.equals("RETIRE")) {
                var old=tx.enterprise().vaultKey(version);
                if(old==null||!old.state().equals("READ_ONLY")||tx.enterprise().secrets().stream().anyMatch(secret->secret.keyVersion()==version))throw Failure.conflict();
                tx.enterprise().updateVaultKey(new EnterpriseStore.VaultKey(old.version(),"RETIRED",old.providerReference(),old.wrappedEncryptionKey(),old.wrappedRetryKey()),"READ_ONLY");
            } else {
                if(current.version()!=version)throw Failure.conflict();
                if(operation.equals("ROTATE"))new VaultService(platformStore(),codec,keys).rotate(tenant,tx);else keys.rewrap(tenant,tx);
            }
            var before=tx.load();tx.save(before,new io.ololabs.toolgate.control.domain.Directory(before.revision()+1,before.entries()));
            tx.audit(actor,"VAULT_KEY_"+operation,"tenant-vault",before.revision()+1,requestId,digest);
            var reply=new Store.Reply(200,codec.json(Map.of("keyVersion",keys.current(tx).version())),before.revision()+1);tx.remember(actor,requestId,digest,reply);return reply;
        });
    }
    public void suspended(TenantId tenant, boolean suspended) {
        platformStore().platformTransaction(tenant, (c, tx) -> {
            try (var s = c.prepareStatement("UPDATE control_tenants SET status=?,updated_at=? WHERE tenant_id=? AND status=?")) {
                s.setString(1, suspended ? "SUSPENDED" : "ACTIVE"); s.setLong(2, System.currentTimeMillis()); s.setString(3, tenant.value());
                s.setString(4, suspended ? "ACTIVE" : "SUSPENDED"); if (s.executeUpdate() != 1) throw Failure.conflict(); return null;
            } catch (SQLException failure) { throw Failure.unavailable(); }
        });
    }
}
