// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Set;

/** Delegate opaque runtime bearer validation to Gateway on the fixed local proxy routes.
 * Control JWT administration and client mTLS routes keep their existing authentication. */
@ApplicationScoped
public class QuickstartRuntimeAuthentication {
    static final String BEARER = QuickstartRuntimeAuthentication.class.getName()+".bearer";
    private static final Set<String> ROUTES = Set.of("/mcp", "/access/invocations", "/access/catalog", "/v1/chat/completions");
    @Inject org.eclipse.microprofile.config.Config config;
    static boolean delegated(boolean enabled, String method, String path) {
        return enabled && "POST".equals(method) && ROUTES.contains(path);
    }
    void register(@Observes Filters filters) {
        if(!config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false)) return;
        filters.register(context -> {
            if(delegated(true,context.request().method().name(),context.normalizedPath())) {
                var values=context.request().headers().getAll("Authorization");
                if(values.size()==1) {
                    context.put(BEARER,values.getFirst());
                    context.request().headers().remove("Authorization");
                }
            }
            context.next();
        },10000);
    }
}
