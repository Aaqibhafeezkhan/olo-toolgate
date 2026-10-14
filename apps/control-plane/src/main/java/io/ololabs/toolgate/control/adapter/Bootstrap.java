// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.agroal.api.AgroalDataSource;
import io.ololabs.toolgate.control.application.DirectoryService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;

/** Refuse incomplete authentication and overprivileged database configuration at startup. */
@ApplicationScoped
public class Bootstrap {
    @Inject Config config;
    @Inject jakarta.enterprise.inject.Instance<AgroalDataSource> source;
    @Produces @ApplicationScoped
    DirectoryService service(PostgresStore store, ContractCodec codec) {
        var limits = limits();
        return new DirectoryService(store, codec, limits.records(), limits.bytes());
    }
    @Produces @ApplicationScoped
    io.ololabs.toolgate.control.application.ConfigurationChanges configurationChanges(PostgresStore store,ContractCodec codec){var l=limits();return new io.ololabs.toolgate.control.application.ConfigurationChanges(store,codec,java.time.Clock.systemUTC(),l.records(),l.bytes());}
    @Produces @ApplicationScoped
    io.ololabs.toolgate.control.application.ServerSettingsService serverSettings(PostgresStore store,ContractCodec codec,io.ololabs.toolgate.control.application.EndpointService endpoint,io.ololabs.toolgate.control.application.ConfigurationChanges changes){return new io.ololabs.toolgate.control.application.ServerSettingsService(store,codec,endpoint.checkInPolicy()).reviews(changes);}
    private record Limits(int records, int bytes) {}
    private Limits limits() {
        var records = config.getValue("toolgate.control.max-records", Integer.class);
        var bytes = config.getValue("toolgate.control.max-config-bytes", Integer.class);
        if (records < 1 || records > 512 || bytes < 1024 || bytes > 1048576) throw new IllegalStateException("Invalid directory limits");
        return new Limits(records, bytes);
    }
    void start(@Observes StartupEvent event) {
        // Validate even when CDI has not yet materialized the lazy application bean.
        limits();
        var issuer = java.net.URI.create(config.getValue("mp.jwt.verify.issuer", String.class));
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null || issuer.getQuery() != null || issuer.getFragment() != null) {
            throw new IllegalStateException("JWT issuer must be an explicit HTTPS identity provider");
        }
        if (config.getValue("mp.jwt.verify.audiences", String.class).isBlank()) throw new IllegalStateException("JWT audience required");
        if (!config.getValue("mp.jwt.verify.publickey.algorithm", String.class).equals("RS256")) throw new IllegalStateException("Only RS256 is supported");
        try {
            var location = config.getValue("mp.jwt.verify.publickey.location", String.class);
            var path = location.startsWith("file:") ? Path.of(java.net.URI.create(location)) : Path.of(location);
            var pem = ProtectedCustody.text(path,16384);
            var encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            var key = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(encoded)));
            if (((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() < 2048) throw new IllegalStateException("Weak verification key");
        } catch (java.io.IOException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("A local RSA public verification key is required");
        }
        if (config.getOptionalValue("toolgate.quickstart.enabled", Boolean.class).orElse(false)
            && !config.getOptionalValue("toolgate.quickstart.storage", String.class).orElse("sqlite").equals("postgresql")) return;
        var runtimeUser = config.getValue("quarkus.datasource.username", String.class);
        var url = config.getValue("quarkus.datasource.jdbc.url", String.class);
        if (!url.startsWith("jdbc:postgresql://") || (!config.getValue("toolgate.control.development-mode", Boolean.class)
                && !url.matches(".*[?&]sslmode=verify-full(&.*)?"))) throw new IllegalStateException("Production PostgreSQL requires verify-full TLS");
        var migrationUser = config.getValue("quarkus.flyway.username", String.class);
        config.getValue("quarkus.flyway.password", String.class);
        if (runtimeUser.equals(migrationUser)) throw new IllegalStateException("Separate runtime and migration credentials required");
        try (var connection = source.get().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT has_table_privilege(current_user,'control_audit','UPDATE') OR has_table_privilege(current_user,'control_policy_bundles','UPDATE'), has_table_privilege(current_user,'control_audit','DELETE') OR has_table_privilege(current_user,'control_policy_bundles','DELETE'), has_schema_privilege(current_user,'public','CREATE'), rolsuper FROM pg_roles WHERE rolname=current_user")) {
            if (!rows.next() || rows.getBoolean(1) || rows.getBoolean(2) || rows.getBoolean(3) || rows.getBoolean(4)) throw new IllegalStateException("Runtime database role is overprivileged");
        } catch (java.sql.SQLException e) { throw new IllegalStateException("Runtime database role validation failed"); }
    }
}
