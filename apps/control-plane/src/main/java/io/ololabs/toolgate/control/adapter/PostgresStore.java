// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.agroal.api.AgroalDataSource;
import io.ololabs.toolgate.control.application.Store;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.McpStore;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.control.domain.Ids.RecordId;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** JDBC adapter with database-backed tenant serialization and atomic audit/replay storage. */
@ApplicationScoped
public class PostgresStore implements Store {
    private final javax.sql.DataSource dataSource;
    private final ContractCodec codec;
    private final DirectoryCache directoryCache;
    private boolean ownsDataSource;
    @Inject
    public PostgresStore(jakarta.enterprise.inject.Instance<AgroalDataSource> dataSource, ContractCodec codec, org.eclipse.microprofile.config.Config config) {
        this(config.getOptionalValue("toolgate.quickstart.enabled", Boolean.class).orElse(false)
            && !config.getOptionalValue("toolgate.quickstart.storage", String.class).orElse("sqlite").equals("postgresql")
            ? SqliteState.openPooled(java.nio.file.Path.of(config.getValue("toolgate.quickstart.database", String.class))) : (javax.sql.DataSource) dataSource.get(), codec);
        ownsDataSource = config.getOptionalValue("toolgate.quickstart.enabled", Boolean.class).orElse(false)
            && !config.getOptionalValue("toolgate.quickstart.storage", String.class).orElse("sqlite").equals("postgresql");
    }
    public PostgresStore(javax.sql.DataSource dataSource, ContractCodec codec) { this(dataSource, codec, new DirectoryCache()); }
    PostgresStore(javax.sql.DataSource dataSource, ContractCodec codec, DirectoryCache cache) {
        this.dataSource = dataSource; this.codec = codec; this.directoryCache = cache;
    }
    @jakarta.annotation.PreDestroy
    void close() { if (ownsDataSource && dataSource instanceof AgroalDataSource pool) pool.close(); }
    public java.util.List<TenantId> tenants(){try(var connection=dataSource.getConnection();var s=connection.prepareStatement("SELECT tenant_id FROM control_tenants ORDER BY tenant_id LIMIT 10000");var rows=s.executeQuery()){var ids=new java.util.ArrayList<TenantId>();while(rows.next())ids.add(new TenantId(rows.getString(1)));return java.util.List.copyOf(ids);}catch(SQLException failure){throw Failure.unavailable();}}
    public <T> T transaction(TenantId tenant, boolean write, Function<Session, T> work) {
        try (var connection = dataSource.getConnection()) {
            boolean local = SqliteState.local(connection);
            if (local) {
                // WAL readers use a stable snapshot without reserving the single writer.
                connection.unwrap(org.sqlite.SQLiteConnection.class).getConnectionConfig()
                    .setTransactionMode(write ? org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE : org.sqlite.SQLiteConfig.TransactionMode.DEFERRED);
                try (var statement = connection.createStatement()) { statement.execute("PRAGMA query_only = " + (write ? "OFF" : "ON")); }
            }
            connection.setAutoCommit(false);
            if (!local) {
                connection.setTransactionIsolation(write ? Connection.TRANSACTION_READ_COMMITTED : Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(!write);
            }
            try {
                if (!local) try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '5s'"); statement.execute("SET LOCAL lock_timeout = '3s'");
                }
                if (write && !local) try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    statement.setString(1, tenant.value()); statement.execute();
                }
                var session = new JdbcSession(connection, tenant.value());
                var result = work.apply(session);
                // SQLite commit() starts another transaction immediately; closing this
                // connection needs no second writer reservation after a successful commit.
                if (local) connection.setAutoCommit(true); else connection.commit();
                // Never publish uncommitted data, including changes later rolled back by the caller.
                if (session.directoryDirty) directoryCache.invalidate(tenant.value());
                else if (session.directoryEntry != null) directoryCache.put(tenant.value(), session.directoryEntry);
                return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException failure) throw failure;
                throw Failure.unavailable();
            } finally {
                if (local) {
                    // SQLite-only state is invisible to the pool's JDBC state tracker.
                    if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
                    try (var s = connection.createStatement()) { s.execute("PRAGMA query_only = OFF"); }
                    connection.unwrap(org.sqlite.SQLiteConnection.class).getConnectionConfig()
                        .setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
                }
            }
        } catch (SQLException e) { throw Failure.unavailable(); }
    }
    private final class JdbcSession implements Session {
        private final Connection connection;
        private final String tenant;
        private DirectoryCache.Entry directoryEntry;
        private boolean directoryDirty;
        private void invalidateDirectory() {
            directoryDirty = true; directoryEntry = null; directoryCache.invalidate(tenant);
        }
        JdbcSession(Connection connection, String tenant) { this.connection = connection; this.tenant = tenant; }
        public io.ololabs.toolgate.control.application.EnterpriseStore enterprise() {return new PostgresEnterpriseStore(connection,tenant,codec);}
        public io.ololabs.toolgate.control.application.FleetStore fleet() { return new PostgresFleetStore(connection, tenant); }
        public io.ololabs.toolgate.control.application.BuilderStore builder() { return new PostgresBuilderStore(connection, tenant); }
        private EnrollmentRecord enrollmentRows(java.sql.PreparedStatement statement) throws SQLException {
            try (statement; var rows=statement.executeQuery()) {
                return rows.next() ? new EnrollmentRecord(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),
                    rows.getString(5),rows.getString(6),rows.getString(7),rows.getLong(8),rows.getLong(9)) : null;
            }
        }
        public EnrollmentRecord enrollment(String id) {
            try { return enrollmentRows(statement("SELECT enrollment_id,code_digest,device_digest,document,csr,user_id,certificate,expires_at,last_poll FROM control_enrollments WHERE tenant_id=? AND enrollment_id=?",id)); }
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public EnrollmentRecord enrollmentCode(String digest) {
            try { return enrollmentRows(statement("SELECT enrollment_id,code_digest,device_digest,document,csr,user_id,certificate,expires_at,last_poll FROM control_enrollments WHERE tenant_id=? AND code_digest=?",digest)); }
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public void saveEnrollment(EnrollmentRecord e) {
            try (var s=statement("INSERT INTO control_enrollments (tenant_id,enrollment_id,code_digest,device_digest,document,csr,user_id,certificate,expires_at,last_poll) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id,enrollment_id) DO UPDATE SET document=excluded.document,user_id=excluded.user_id,certificate=excluded.certificate,last_poll=excluded.last_poll",
                e.id(),e.codeDigest(),e.deviceDigest(),e.document(),e.csr(),e.userId(),e.certificate(),e.expiresAt(),e.lastPoll())) { s.executeUpdate(); }
            catch (SQLException failure) { throw Failure.unavailable(); }
        }
        public long pendingEnrollments(long now) {
            try (var s=statement("SELECT count(*) FROM control_enrollments WHERE tenant_id=? AND expires_at>?",now);var rows=s.executeQuery()) {rows.next();return rows.getLong(1);}
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public java.util.List<EnrollmentRecord> enrollments(long now) {
            try (var s=statement("SELECT enrollment_id,code_digest,device_digest,document,csr,user_id,certificate,expires_at,last_poll FROM control_enrollments WHERE tenant_id=? AND expires_at>? ORDER BY expires_at,enrollment_id LIMIT 32",now);var rows=s.executeQuery()) {
                var result=new java.util.ArrayList<EnrollmentRecord>();
                while(rows.next())result.add(new EnrollmentRecord(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getLong(8),rows.getLong(9)));
                return java.util.List.copyOf(result);
            }catch(SQLException failure){throw Failure.unavailable();}
        }
        public void pruneEnrollments(long now) {
            try (var s=statement("DELETE FROM control_enrollments WHERE tenant_id=? AND expires_at<=?",now)) {s.executeUpdate();}
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public void deleteEnrollments(String deviceId) {
            try(var s=statement("DELETE FROM control_enrollments WHERE tenant_id=? AND document::jsonb->>'deviceId'=?",deviceId)){s.executeUpdate();}
            catch(SQLException failure){throw Failure.unavailable();}
        }
        private EndpointRecord endpointRows(java.sql.PreparedStatement statement) throws SQLException {
            try (statement;var rows=statement.executeQuery()) {return rows.next()?new EndpointRecord(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6)):null;}
        }
        public EndpointRecord endpoint(String id) {
            try {return endpointRows(statement("SELECT device_id,key_fingerprint,document,csr,report_digest,acknowledgment FROM control_endpoints WHERE tenant_id=? AND device_id=?",id));}
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public EndpointRecord endpointKey(String fingerprint) {
            try {return endpointRows(statement("SELECT device_id,key_fingerprint,document,csr,report_digest,acknowledgment FROM control_endpoints WHERE tenant_id=? AND key_fingerprint=?",fingerprint));}
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public void saveEndpoint(EndpointRecord e) {
            try (var s=statement("INSERT INTO control_endpoints (tenant_id,device_id,key_fingerprint,document,csr,report_digest,acknowledgment) VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id,device_id) DO UPDATE SET document=excluded.document,report_digest=excluded.report_digest,acknowledgment=excluded.acknowledgment",
                e.id(),e.fingerprint(),e.document(),e.csr(),e.reportDigest(),e.acknowledgment())) {s.executeUpdate();}
            catch (SQLException failure) {throw Failure.unavailable();}
        }
        public EndpointConfiguration endpointConfiguration(String deviceId) {
            try (var s=statement("SELECT source_revision,document,acknowledged_digest,local_tools FROM control_endpoint_configurations WHERE tenant_id=? AND device_id=?",deviceId);var rows=s.executeQuery()) {
                return rows.next()?new EndpointConfiguration(deviceId,rows.getLong(1),rows.getString(2),rows.getString(3),rows.getString(4)):null;
            } catch(SQLException failure){throw Failure.unavailable();}
        }
        public void saveEndpointConfiguration(EndpointConfiguration configuration) {
            try(var s=statement("INSERT INTO control_endpoint_configurations (tenant_id,device_id,source_revision,document,acknowledged_digest,local_tools) VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,device_id) DO UPDATE SET source_revision=excluded.source_revision,document=excluded.document,acknowledged_digest=excluded.acknowledged_digest,local_tools=excluded.local_tools",
                configuration.deviceId(),configuration.sourceRevision(),configuration.document(),configuration.acknowledgedDigest(),configuration.localTools())) {
                if(s.executeUpdate()!=1)throw Failure.conflict();
            } catch(SQLException failure){throw Failure.unavailable();}
        }
        public String serverSettings() {
            try (var s=statement("SELECT document FROM control_server_settings WHERE tenant_id=?");var rows=s.executeQuery()) {return rows.next()?rows.getString(1):null;}
            catch(SQLException failure){throw Failure.unavailable();}
        }
        public void saveServerSettings(long revision,String document) {
            invalidateDirectory();
            try(var s=statement("INSERT INTO control_server_settings (tenant_id,revision,document) VALUES (?,?,?) ON CONFLICT (tenant_id) DO UPDATE SET revision=excluded.revision,document=excluded.document WHERE control_server_settings.revision=excluded.revision-1",revision,document)) {
                if(s.executeUpdate()!=1)throw Failure.conflict();
            } catch(SQLException failure){throw Failure.unavailable();}
        }
        public io.ololabs.toolgate.control.application.PackageStore packages() {
            return new io.ololabs.toolgate.control.application.PackageStore() {
                private String text(String sql,Object... parameters){try(var s=statement(sql,parameters);var rows=s.executeQuery()){return rows.next()?rows.getString(1):null;}catch(SQLException failure){throw Failure.unavailable();}}
                private java.util.List<String> texts(String sql,Object... parameters){try(var s=statement(sql,parameters);var rows=s.executeQuery()){var values=new java.util.ArrayList<String>();while(rows.next())values.add(rows.getString(1));return java.util.List.copyOf(values);}catch(SQLException failure){throw Failure.unavailable();}}
                private void write(String sql,Object... parameters){try(var s=statement(sql,parameters)){if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
                private java.util.List<Version> query(String suffix,Object... parameters){
                    try(var s=statement("SELECT package_digest,package_id,version,uploaded_at,document FROM control_server_packages WHERE tenant_id=? "+suffix,parameters);var rows=s.executeQuery()){
                        var values=new java.util.ArrayList<Version>();while(rows.next())values.add(new Version(rows.getString(1),rows.getString(2),rows.getString(3),rows.getLong(4),rows.getString(5)));return java.util.List.copyOf(values);
                    }catch(SQLException failure){throw Failure.unavailable();}
                }
                public Version version(String digest){var rows=query("AND package_digest=?",digest);return rows.isEmpty()?null:rows.getFirst();}
                public java.util.List<Version> versions(int limit){return query("ORDER BY uploaded_at DESC,package_digest LIMIT ?",limit);}
                public byte[] content(String digest){try(var s=statement("SELECT content FROM control_server_packages WHERE tenant_id=? AND package_digest=?",digest);var rows=s.executeQuery()){return rows.next()?rows.getBytes(1):null;}catch(SQLException failure){throw Failure.unavailable();}}
                public void saveVersion(Version v,byte[] content){write("INSERT INTO control_server_packages (tenant_id,package_digest,package_id,version,uploaded_at,document,content) VALUES (?,?,?,?,?,?,?)",v.digest(),v.packageId(),v.version(),v.uploadedAt(),v.document(),content);}
                public String document(String digest){return text("SELECT document FROM control_server_documents WHERE tenant_id=? AND digest=?",digest);}
                public void saveDocument(String digest,String document){if(document(digest)==null)write("INSERT INTO control_server_documents (tenant_id,digest,document) VALUES (?,?,?)",digest,document);}
                public String deployment(String packageId){return text("SELECT document FROM control_server_deployments WHERE tenant_id=? AND package_id=?",packageId);}
                public java.util.List<String> deployments(){return texts("SELECT document FROM control_server_deployments WHERE tenant_id=? ORDER BY package_id LIMIT 256");}
                public void saveDeployment(String packageId,String document){write("INSERT INTO control_server_deployments (tenant_id,package_id,document) VALUES (?,?,?) ON CONFLICT (tenant_id,package_id) DO UPDATE SET document=excluded.document",packageId,document);}
                public String toolHost(String deviceId){return text("SELECT document FROM control_tool_hosts WHERE tenant_id=? AND device_id=?",deviceId);}
                public void saveToolHost(String deviceId,String document){write("INSERT INTO control_tool_hosts (tenant_id,device_id,document) VALUES (?,?,?) ON CONFLICT (tenant_id,device_id) DO UPDATE SET document=excluded.document",deviceId,document);}
                private Relayed relayedRow(String suffix,Object... parameters){
                    try(var s=statement("SELECT invocation_id,host_id,state,expires_at,document FROM control_tool_host_dispatches WHERE tenant_id=? "+suffix,parameters);var rows=s.executeQuery()){
                        return rows.next()?new Relayed(rows.getString(1),rows.getString(2),rows.getString(3),rows.getLong(4),rows.getString(5)):null;
                    }catch(SQLException failure){throw Failure.unavailable();}
                }
                public Relayed relayed(String invocationId){return relayedRow("AND invocation_id=?",invocationId);}
                public Relayed nextRelayed(String hostId,long now){return relayedRow("AND host_id=? AND state='QUEUED' AND expires_at>? ORDER BY created_at,invocation_id LIMIT 1",hostId,now);}
                public void saveRelayed(Relayed r){
                    // Finished dispatches are kept for an hour after their deadline, for the Gateway's last poll.
                    try(var s=statement("DELETE FROM control_tool_host_dispatches WHERE tenant_id=? AND expires_at<?",System.currentTimeMillis()-3600000L)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}
                    write("INSERT INTO control_tool_host_dispatches (tenant_id,invocation_id,host_id,state,expires_at,created_at,document) VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id,invocation_id) DO UPDATE SET state=excluded.state,document=excluded.document",
                        r.invocationId(),r.hostId(),r.state(),r.expiresAt(),System.currentTimeMillis(),r.document());
                }
            };
        }
        public McpStore mcp() {
            return new McpStore() {
                private Row row(java.sql.ResultSet rows)throws SQLException{return new Row(rows.getString(1),rows.getString(2),rows.getString(3),rows.getLong(4),rows.getString(5),rows.getString(6),rows.getString(7));}
                private java.util.List<Row> query(String suffix,Object... parameters){
                    try(var s=statement("SELECT request_id,device_id,state,expires_at,record,task,result FROM control_mcp_requests WHERE tenant_id=? "+suffix,parameters);var rows=s.executeQuery()){
                        var values=new java.util.ArrayList<Row>();while(rows.next())values.add(row(rows));return java.util.List.copyOf(values);
                    }catch(SQLException failure){throw Failure.unavailable();}
                }
                public Row get(String id){var rows=query("AND request_id=?",id);return rows.isEmpty()?null:rows.getFirst();}
                public java.util.List<Row> page(int limit){return query("ORDER BY received_at DESC,request_id LIMIT ?",limit);}
                public java.util.List<Row> pending(String deviceId,int limit){return query("AND device_id=? AND state IN ('WAITING_FOR_POLL','SUBMITTED') ORDER BY received_at,request_id LIMIT ?",deviceId,limit);}
                public void save(Row row){try(var s=statement("INSERT INTO control_mcp_requests (tenant_id,request_id,device_id,state,expires_at,received_at,record,task,result) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id,request_id) DO UPDATE SET state=excluded.state,record=excluded.record,result=excluded.result",
                    row.id(),row.deviceId(),row.state(),row.expiresAt(),codec.model(row.record(),io.ololabs.toolgate.contracts.RemoteToolRecord.class).receivedAtUnixMs(),row.record(),row.task(),row.result())){if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}}
                public void prune(long before){try(var s=statement("DELETE FROM control_mcp_requests WHERE tenant_id=? AND expires_at<?",before)){s.executeUpdate();}catch(SQLException failure){throw Failure.unavailable();}}
                public long count(){try(var s=statement("SELECT count(*) FROM control_mcp_requests WHERE tenant_id=?");var rows=s.executeQuery()){rows.next();return rows.getLong(1);}catch(SQLException failure){throw Failure.unavailable();}}
            };
        }
        public long bundleSequence() {
            try (var statement = statement("SELECT max(n) FROM (SELECT COALESCE(max(sequence),0) n FROM control_policy_bundles WHERE tenant_id=? UNION ALL SELECT snapshot_sequence n FROM control_recovery_floors WHERE tenant_id=?) floors",tenant); var rows = statement.executeQuery()) {
                rows.next(); return rows.getLong(1);
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public BundleRecord bundle(long sequence) {
            try (var statement = statement("SELECT document,policy,directory_revision FROM control_policy_bundles WHERE tenant_id=? AND sequence=?", sequence); var rows = statement.executeQuery()) {
                return rows.next() ? new BundleRecord(sequence, rows.getString(1), rows.getString(2), rows.getLong(3)) : null;
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public void publishBundle(BundleRecord bundle) {
            try (var statement = statement("INSERT INTO control_policy_bundles (tenant_id,sequence,directory_revision,document,policy) VALUES (?,?,?,?,?)",
                    bundle.sequence(), bundle.directoryRevision(), bundle.document(), bundle.policy())) {
                statement.executeUpdate();
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public long approvalClock(long now) {
            try (var statement = statement("INSERT INTO control_approval_clocks (tenant_id,observed_at) VALUES (?,?) ON CONFLICT (tenant_id) DO UPDATE SET observed_at=GREATEST(control_approval_clocks.observed_at,excluded.observed_at) RETURNING observed_at", now);
                 var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        private java.sql.PreparedStatement statement(String sql, Object... params) throws SQLException {
            var statement = connection.prepareStatement(SqliteState.sql(connection, sql)); statement.setString(1, tenant);
            for (int i = 0; i < params.length; i++) statement.setObject(i + 2, params[i]); return statement;
        }
        public Directory load() {
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    var version = directoryVersion();
                    if (directoryEntry != null && directoryCache.fresh(directoryEntry) && directoryEntry.version.equals(version)) return directoryEntry.directory;
                    var cached = directoryDirty ? null : directoryCache.get(tenant, version);
                    if (cached != null) { directoryEntry = cached; return cached.directory; }
                    var entries = new HashMap<RecordId, Directory.Entry>();
                    try (var statement = statement("SELECT kind,document::text FROM control_records WHERE tenant_id=?"); var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var entry = codec.entry(Kind.valueOf(rows.getString(1)), rows.getString(2)); entries.put(entry.id(), entry);
                        }
                    }
                    // READ COMMITTED may see a concurrent commit between the version and record queries.
                    if (!version.equals(directoryVersion())) continue;
                    directoryEntry = directoryCache.entry(version, new Directory(version.revision(), entries));
                    return directoryEntry.directory;
                }
                throw Failure.unavailable();
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        private DirectoryCache.Version directoryVersion() throws SQLException {
            try (var s = statement("SELECT revision,directory_cache_generation FROM control_tenants WHERE tenant_id=?"); var rows = s.executeQuery()) {
                return rows.next() ? new DirectoryCache.Version(rows.getLong(1), rows.getLong(2)) : new DirectoryCache.Version(0, -1);
            }
        }
        public String adoptionDigest(TenantId requestedTenant, io.ololabs.toolgate.control.application.Codec requestedCodec) {
            if (!tenant.equals(requestedTenant.value())) throw Failure.validation();
            var directory = load();
            return directoryEntry.digest(() -> io.ololabs.toolgate.control.application.EndpointAdoptions.digest(codec, requestedTenant, directory));
        }
        public void observeIdentity(io.ololabs.toolgate.contracts.ControlIdentityBinding next){
            var entry=load().entries().get(Kind.IDENTITY_BINDING.id(next.id()));if(entry==null)throw Failure.conflict();var old=codec.model(entry.document(),io.ololabs.toolgate.contracts.ControlIdentityBinding.class);
            var normalized=new io.ololabs.toolgate.contracts.ControlIdentityBinding(next.id(),next.name(),next.enabled(),old.revision(),next.userId(),next.issuer(),next.subject(),next.sessionEpoch(),next.firstSeenUnixMs(),old.lastAttemptUnixMs(),old.attemptCount(),next.registrationReason(),next.sessionsValidAfterUnixMs());
            if(!old.equals(normalized)||next.revision()!=old.revision()+1||next.lastAttemptUnixMs()<old.lastAttemptUnixMs()||next.attemptCount()<old.attemptCount())throw Failure.conflict();
            invalidateDirectory();
            try(var s=connection.prepareStatement(SqliteState.sql(connection,"UPDATE control_records SET revision=?,document=?::jsonb WHERE tenant_id=? AND kind='IDENTITY_BINDING' AND record_id=? AND revision=?"))){s.setLong(1,next.revision());s.setString(2,codec.json(next));s.setString(3,tenant);s.setString(4,next.id());s.setLong(5,old.revision());if(s.executeUpdate()!=1)throw Failure.conflict();}catch(SQLException failure){throw Failure.unavailable();}
        }
        public void save(Directory before, Directory after) {
            try {
                io.ololabs.toolgate.control.application.BootstrapAdministrator.validate(this,before,after,codec);
                if(after.equals(before))return;
                if(after.revision()<before.revision() || !after.entries().equals(before.entries())&&after.revision()<=before.revision())throw Failure.conflict();
                invalidateDirectory();
                try (var statement = statement("INSERT INTO control_tenants (tenant_id,revision,authorization_epoch,cutover_revision) VALUES (?,?,?,?) ON CONFLICT (tenant_id) DO UPDATE SET revision=excluded.revision,authorization_epoch=GREATEST(control_tenants.authorization_epoch+1,excluded.authorization_epoch)", after.revision(),after.revision(),after.revision())) {
                    statement.executeUpdate();
                }
                if(after.revision()!=before.revision())try(var statement=statement("WITH current_auth AS (SELECT tenant_id,revision,authorization_epoch FROM control_tenants WHERE tenant_id=?) INSERT INTO control_authorization_outbox(tenant_id,revision,authorization_epoch,occurred_at) SELECT tenant_id,revision,authorization_epoch,? FROM current_auth",System.currentTimeMillis())) {statement.executeUpdate();}
                for (var id : before.entries().keySet()) if (!after.entries().containsKey(id)) {
                    if(id.kind()==Kind.DEVICE){
                        var row=endpoint(id.value());
                        if(row!=null){
                            var device=codec.model(row.document(),io.ololabs.toolgate.contracts.EndpointDeviceRecord.class);
                            // Deletion removes approval, but only explicit revocation retires the key.
                            if(device.state()!=io.ololabs.toolgate.contracts.EndpointState.REVOKED){
                                var reset=new io.ololabs.toolgate.contracts.EndpointDeviceRecord(device.deviceId(),device.tenantId(),device.userId(),device.keyFingerprint(),io.ololabs.toolgate.contracts.EndpointState.UNENROLLED,device.revision()+1,0L,device.reportSequence(),null,null,false,(device.approvalRevision()==null?1:device.approvalRevision())+1,null,null);
                                saveEndpoint(new EndpointRecord(row.id(),row.fingerprint(),codec.json(reset),row.csr(),row.reportDigest(),row.acknowledgment()));
                            }
                            // Outstanding enrollment codes cannot restore the deleted approval.
                            deleteEnrollments(id.value());
                        }
                    }
                    try (var statement = statement("DELETE FROM control_records WHERE tenant_id=? AND kind=? AND record_id=?", id.kind().name(), id.value())) { statement.executeUpdate(); }
                }
                for (var entry : after.entries().values()) if (!entry.equals(before.entries().get(entry.id()))) {
                    if(entry.id().kind()==Kind.WORKLOAD_BINDING)enterprise().rememberCredential(codec.model(entry.document(),io.ololabs.toolgate.contracts.ControlWorkloadBinding.class).credentialSha256());
                    try (var statement = statement("INSERT INTO control_record_ids (tenant_id,kind,record_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", entry.id().kind().name(), entry.id().value())) { statement.executeUpdate(); }
                    try (var statement = statement("INSERT INTO control_records (tenant_id,kind,record_id,revision,document) VALUES (?,?,?,?,?::jsonb) ON CONFLICT (tenant_id,kind,record_id) DO UPDATE SET revision=excluded.revision,document=excluded.document",
                            entry.id().kind().name(), entry.id().value(), entry.revision(), entry.document())) { statement.executeUpdate(); }
                }
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public boolean used(RecordId id) {
            try (var statement = statement("SELECT 1 FROM control_record_ids WHERE tenant_id=? AND kind=? AND record_id=?", id.kind().name(), id.value()); var rows = statement.executeQuery()) {
                return rows.next();
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public String bootstrapAdminUserId() {
            try(var s=statement("SELECT user_id FROM control_bootstrap_admins WHERE tenant_id=?");var rows=s.executeQuery()){return rows.next()?rows.getString(1):null;}
            catch(SQLException failure){throw Failure.unavailable();}
        }
        public ConsoleDeviceRecord consoleDevice(String sessionId){
            try(var s=statement("SELECT session_id,user_id,device_id,nonce_digest,code_digest,csr,fingerprint,expires_at,verified_at FROM control_console_devices WHERE tenant_id=? AND session_id=?",sessionId);var rows=s.executeQuery()){
                return rows.next()?new ConsoleDeviceRecord(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getLong(8),rows.getLong(9)):null;
            }catch(SQLException failure){throw Failure.unavailable();}
        }
        public void saveConsoleDevice(ConsoleDeviceRecord record){
            try(var cleanup=statement("DELETE FROM control_console_devices WHERE tenant_id=? AND expires_at<?",record.verifiedAt()>0?record.verifiedAt():record.expiresAt()-120000L)){cleanup.executeUpdate();}
            catch(SQLException failure){throw Failure.unavailable();}
            try(var s=statement("INSERT INTO control_console_devices(tenant_id,session_id,user_id,device_id,nonce_digest,code_digest,csr,fingerprint,expires_at,verified_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,session_id) DO UPDATE SET user_id=excluded.user_id,device_id=excluded.device_id,nonce_digest=excluded.nonce_digest,code_digest=excluded.code_digest,csr=excluded.csr,fingerprint=excluded.fingerprint,expires_at=excluded.expires_at,verified_at=excluded.verified_at",record.sessionId(),record.userId(),record.deviceId(),record.nonceDigest(),record.codeDigest(),record.csr(),record.fingerprint(),record.expiresAt(),record.verifiedAt())){s.executeUpdate();}
            catch(SQLException failure){throw Failure.unavailable();}
        }
        public void protectBootstrapAdmin(String userId) {
            io.ololabs.toolgate.control.domain.Ids.valid(userId);
            try(var s=statement("INSERT INTO control_bootstrap_admins(tenant_id,user_id) VALUES (?,?) ON CONFLICT DO NOTHING",userId)){s.executeUpdate();}
            catch(SQLException failure){throw Failure.unavailable();}
            if(!userId.equals(bootstrapAdminUserId()))throw Failure.conflict();
        }
        public Reply replay(String actor, String key, String digest) {
            try {
                try (var statement = statement("DELETE FROM control_idempotency WHERE tenant_id=? AND expires_at <= clock_timestamp()")) { statement.executeUpdate(); }
                try (var statement = statement("SELECT request_digest,status,body,revision FROM control_idempotency WHERE tenant_id=? AND actor_id=? AND key=?", actor, key); var rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    if (!rows.getString(1).equals(digest)) throw Failure.conflict();
                    return new Reply(rows.getInt(2), rows.getString(3), rows.getLong(4));
                }
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public void remember(String actor, String key, String digest, Reply reply) {
            try {
                try (var statement = statement("SELECT count(*) FROM control_idempotency WHERE tenant_id=?"); var rows = statement.executeQuery()) {
                    rows.next(); if (rows.getLong(1) >= 10000) throw Failure.conflict();
                }
                try (var statement = statement("INSERT INTO control_idempotency (tenant_id,actor_id,key,request_digest,status,body,revision) VALUES (?,?,?,?,?,?,?)",
                    actor, key, digest, reply.status(), reply.body(), reply.revision())) { statement.executeUpdate(); }
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
        public void audit(String actor, String operation, String target, long revision, String requestId, String digest) {
            try (var statement = statement("INSERT INTO control_audit (tenant_id,actor_id,operation,target,revision,request_id,request_digest) VALUES (?,?,?,?,?,?,?)",
                actor, operation, target, revision, requestId, digest)) { statement.executeUpdate(); }
            catch (SQLException e) { throw Failure.unavailable(); }
        }
        public String auditPage(long after, int limit) {
            try (var statement = statement("SELECT sequence,actor_id,operation,target,revision,request_id,request_digest,occurred_at FROM control_audit WHERE tenant_id=? AND sequence>? ORDER BY sequence LIMIT ?", after, limit + 1);
                 var rows = statement.executeQuery()) {
                var items = new java.util.ArrayList<Object>(); long sequence = after; boolean more = false;
                while (rows.next()) {
                    if (items.size() == limit) { more = true; break; }
                    sequence = rows.getLong(1);
                    items.add(Map.of("sequence", sequence, "tenantId", tenant, "actorId", rows.getString(2), "operation", rows.getString(3),
                        "target", rows.getString(4), "revision", rows.getLong(5), "requestId", rows.getString(6), "requestDigest", rows.getString(7),
                        "occurredAt", SqliteState.local(connection) ? rows.getString(8) : rows.getObject(8, java.time.OffsetDateTime.class).toInstant().toString()));
                }
                return codec.json(more ? Map.of("items", items, "nextCursor", Long.toString(sequence)) : Map.of("items", items));
            } catch (SQLException e) { throw Failure.unavailable(); }
        }
    }
}
