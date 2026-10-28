// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.ServerSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.util.Locale;

/** First-initialization environment inputs. The tenant coordinator owns the only commit/activation boundary. */
@ApplicationScoped
public class ConfigurationImportBootstrap {
    @Inject Config config;
    @Inject ContractCodec codec;
    com.fasterxml.jackson.databind.JsonNode environment() {
        var values=new java.util.TreeMap<String,Object>();var approval=new java.util.TreeMap<String,Object>();
        var enabled = config.getOptionalValue("toolgate.settings.auto-approve-devices", Boolean.class);
        var duration = config.getOptionalValue("toolgate.settings.auto-approve-duration-days", String.class).map(ConfigurationImportBootstrap::days);
        var owner = config.getOptionalValue("toolgate.settings.auto-approve-owner", String.class);
        enabled.ifPresent(v->approval.put("autoApprove",v));duration.ifPresent(v->approval.put("durationMs",v*ServerSettingsService.DAY_MS));owner.ifPresent(v->approval.put("ownerUserId",v));
        if(!approval.isEmpty())values.put("deviceApproval",approval);
        config.getOptionalValue("toolgate.gateway-name",String.class).ifPresent(v->values.put("gateway",java.util.Map.of("gatewayName",v)));
        config.getOptionalValue("toolgate.config.required-reviews",Long.class).ifPresent(v->values.put("configuration",java.util.Map.of("requiredReviews",v)));
        return codec.configuration(codec.json(values),false);
    }

    /** Accepts 30, 30d, 30day or 30days. */
    public static long days(String value) {
        var matcher = java.util.regex.Pattern.compile("([0-9]{1,4})\\s*(d|day|days)?").matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalStateException("Auto-approve duration must be a number of days, such as 30");
        return Long.parseLong(matcher.group(1));
    }
}
