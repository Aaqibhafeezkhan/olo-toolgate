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
import java.util.Locale;

/** Optional bring-up seeding of device auto-approval from TOOLGATE_SETTINGS_AUTO_APPROVE_* environment variables.
 * The other settings arrive in the configuration snapshot (settings), or default from TOOLGATE_GATEWAY_NAME and
 * TOOLGATE_CONFIG_REQUIRED_REVIEWS until saved. Settings are seeded once unless overwrite is enabled.
 */
@ApplicationScoped
public class ConfigurationImportBootstrap {
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ConfigurationImportBootstrap.class);
    @Inject Config config;
    @Inject ContractCodec codec;
    @Inject ServerSettingsService settings;
    @Inject io.ololabs.toolgate.control.application.EndpointService endpoint;

    void start(@Observes StartupEvent event) {
        var requested = requested();
        if (requested == null) return;
        var tenant = config.getOptionalValue("toolgate.control.endpoint.tenant-id", String.class)
            .orElseThrow(() -> new IllegalStateException("Settings import requires toolgate.control.endpoint.tenant-id"));
        boolean overwrite = config.getOptionalValue("toolgate.config.import-overwrite", Boolean.class).orElse(false);
        try {
            boolean applied = settings.importAtStartup(new Ids.TenantId(Ids.valid(tenant)), requested, overwrite, "startup-" + java.util.UUID.randomUUID());
            LOG.info(codec.json(java.util.Map.of("service", "control", "event", "configuration_import", "applied", applied, "autoApproveDevices", requested.deviceApproval().autoApprove())));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Startup settings import was rejected; check the TOOLGATE_SETTINGS_* values", failure);
        }
    }

    /** Defaults with the environment's auto-approval values, or null when none is configured. */
    ControlServerSettings requested() {
        var base = ServerSettingsService.defaults(endpoint.checkInPolicy());
        var enabled = config.getOptionalValue("toolgate.settings.auto-approve-devices", Boolean.class);
        var duration = config.getOptionalValue("toolgate.settings.auto-approve-duration-days", String.class).map(ConfigurationImportBootstrap::days);
        var owner = config.getOptionalValue("toolgate.settings.auto-approve-owner", String.class);
        if (enabled.isEmpty() && duration.isEmpty() && owner.isEmpty()) return null;
        var approval = base.deviceApproval();
        return new ControlServerSettings(2L, base.revision(), base.gateway(),
            new DeviceApproval(enabled.orElse(approval.autoApprove()), duration.map(days -> days * ServerSettingsService.DAY_MS).orElse(approval.durationMs()), owner.orElse(approval.ownerUserId())),
            base.clientCheckIn(), base.configuration());
    }

    /** Accepts 30, 30d, 30day or 30days. */
    public static long days(String value) {
        var matcher = java.util.regex.Pattern.compile("([0-9]{1,4})\\s*(d|day|days)?").matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalStateException("Auto-approve duration must be a number of days, such as 30");
        return Long.parseLong(matcher.group(1));
    }
}
