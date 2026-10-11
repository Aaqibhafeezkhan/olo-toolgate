// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.ServerSettingsService;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.v2.ControlServerSettings;
import io.ololabs.toolgate.contracts.v2.DeviceApproval;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.*;
import java.util.Locale;

/** Optional bring-up import of server settings from a mounted folder and environment variables.
 * Folder: server-settings.json in toolgate.config.import-directory, in the exported settings format (formatVersion 2).
 * Environment values override the file. Settings are seeded once unless overwrite is enabled. clientCheckIn in the
 * file is ignored: check-in is env-owned and always comes from TOOLGATE_CONTROL_CLIENT_*.
 */
@ApplicationScoped
public class ConfigurationImportBootstrap {
    static final String SETTINGS_FILE = "server-settings.json";
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ConfigurationImportBootstrap.class);
    @Inject Config config;
    @Inject ContractCodec codec;
    @Inject ServerSettingsService settings;
    @Inject io.ololabs.toolgate.control.application.EndpointService endpoint;

    void start(@Observes StartupEvent event) {
        var requested = requested();
        if (requested == null) return;
        var tenant = config.getOptionalValue("toolgate.control.endpoint.tenant-id", String.class)
            .orElseThrow(() -> new IllegalStateException("Configuration import requires toolgate.control.endpoint.tenant-id"));
        boolean overwrite = config.getOptionalValue("toolgate.config.import-overwrite", Boolean.class).orElse(false);
        try {
            boolean applied = settings.importAtStartup(new Ids.TenantId(Ids.valid(tenant)), requested, overwrite, "startup-" + java.util.UUID.randomUUID());
            LOG.info(codec.json(java.util.Map.of("service", "control", "event", "configuration_import", "applied", applied, "autoApproveDevices", requested.deviceApproval().autoApprove())));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Startup configuration import was rejected; check server-settings.json and TOOLGATE_SETTINGS_* values", failure);
        }
    }

    /** Merged file and environment settings, or null when neither source is configured. */
    ControlServerSettings requested() {
        var base = ServerSettingsService.defaults(endpoint.checkInPolicy());boolean present = false;
        var directory = config.getOptionalValue("toolgate.config.import-directory", String.class);
        if (directory.isPresent()) {
            var file = Path.of(directory.get()).resolve(SETTINGS_FILE);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.size(file) > 16384) throw new IllegalArgumentException();
                    base = codec.model(Files.readString(file), ControlServerSettings.class);present = true;
                } catch (java.io.IOException | RuntimeException failure) {
                    throw new IllegalStateException("Invalid " + SETTINGS_FILE + " in the configuration import directory", failure);
                }
            }
        }
        var enabled = config.getOptionalValue("toolgate.settings.auto-approve-devices", Boolean.class);
        var duration = config.getOptionalValue("toolgate.settings.auto-approve-duration-days", String.class).map(ConfigurationImportBootstrap::days);
        var owner = config.getOptionalValue("toolgate.settings.auto-approve-owner", String.class);
        if (enabled.isEmpty() && duration.isEmpty() && owner.isEmpty() && !present) return null;
        var approval = base.deviceApproval();
        return new ControlServerSettings(2L, base.revision(), base.gateway(),
            new DeviceApproval(enabled.orElse(approval.autoApprove()), duration.map(days -> days * ServerSettingsService.DAY_MS).orElse(approval.durationMs()), owner.orElse(approval.ownerUserId())),
            base.clientCheckIn());
    }

    /** Accepts 30, 30d, 30day or 30days. */
    public static long days(String value) {
        var matcher = java.util.regex.Pattern.compile("([0-9]{1,4})\\s*(d|day|days)?").matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalStateException("Auto-approve duration must be a number of days, such as 30");
        return Long.parseLong(matcher.group(1));
    }
}
