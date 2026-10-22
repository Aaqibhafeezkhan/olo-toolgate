// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import org.eclipse.microprofile.config.Config;

/** Dedicated platform credentials. Tenant runtime cannot obtain this connection through Store. */
@ApplicationScoped
public class PlatformDatabase {
    @Inject Config config;
    private AgroalDataSource source;
    synchronized javax.sql.DataSource source() {
        if (source != null) return source;
        if (config.getOptionalValue("toolgate.quickstart.enabled", Boolean.class).orElse(false)
                && !config.getOptionalValue("toolgate.quickstart.storage", String.class).orElse("sqlite").equals("postgresql")) {
            source = SqliteState.openPooled(Path.of(config.getValue("toolgate.quickstart.database", String.class)));
            return source;
        }
        try {
            String username = config.getValue("toolgate.control.platform.username", String.class);
            if (username.isBlank() || username.equals(config.getValue("quarkus.datasource.username", String.class))
                    || username.equals(config.getValue("quarkus.flyway.username", String.class)))
                throw new IllegalStateException("Separate platform, runtime and migration database identities required");
            String secret = ProtectedCustody.privateText(Path.of(config.getValue("toolgate.control.platform.password-path", String.class)), 4096).strip();
            if (secret.isEmpty()) throw new IllegalStateException("Platform database credential required");
            source = AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier().connectionPoolConfiguration(p -> p
                .minSize(1).initialSize(1).maxSize(4).acquisitionTimeout(java.time.Duration.ofSeconds(3))
                .connectionFactoryConfiguration(f -> f.connectionProviderClass(org.postgresql.Driver.class)
                    .jdbcUrl(config.getValue("quarkus.datasource.jdbc.url", String.class))
                    .principal(new NamePrincipal(username)).credential(new SimplePassword(secret)))));
            try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
                    "SELECT pg_has_role(session_user,'toolgate_control_platform','MEMBER'),rolsuper,rolbypassrls,has_schema_privilege(current_user,'public','CREATE') FROM pg_roles WHERE rolname=current_user")) {
                if (!r.next() || !r.getBoolean(1) || r.getBoolean(2) || r.getBoolean(3) || r.getBoolean(4))
                    throw new IllegalStateException("Invalid platform database privileges");
            }
            return source;
        } catch (Exception failure) {
            if (source != null) { source.close(); source = null; }
            throw new IllegalStateException("Protected platform database configuration required", failure);
        }
    }
    @jakarta.annotation.PreDestroy void close() { if (source != null) source.close(); }
}
