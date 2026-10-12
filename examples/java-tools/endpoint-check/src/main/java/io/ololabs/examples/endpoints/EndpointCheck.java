// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.endpoints;

import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.RawResponse;
import io.ololabs.toolgate.sdk.context.RouteCall;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import io.ololabs.toolgate.sdk.errors.UpstreamError;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is the service up? Calls a health endpoint on the service the administrator points {@code ServiceHost} at, and
 * reports up or down, the HTTP status, the latency and the status the service reports about itself (Spring Boot
 * Actuator, Kubernetes-style {@code /healthz}, or any JSON with a {@code status} field).
 *
 * <p>The tool reaches only that host, over HTTPS, on two declared route shapes ({@code /a} and {@code /a/b}); a model
 * cannot point it anywhere else. Deploy the package once per service, each with its own {@code ServiceHost}.
 */
@ToolPackage(id = "examples.endpoints", name = "Endpoint check", publisher = "ToolGate Examples",
        description = "Checks whether a service's health endpoint is up, how fast it answers and what it reports.")
@Destination(id = "service", hostSetting = "ServiceHost")
@HttpRoute(id = "health.one", destination = "service", method = HttpMethod.GET,
        path = "/{a:[A-Za-z0-9._-]{1,64}}", maxResponseBytes = 65_536, timeoutMs = 10_000)
@HttpRoute(id = "health.two", destination = "service", method = HttpMethod.GET,
        path = "/{a:[A-Za-z0-9._-]{1,64}}/{b:[A-Za-z0-9._-]{1,64}}", maxResponseBytes = 65_536, timeoutMs = 10_000)
public class EndpointCheck {
    static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /**
     * The first "status" in a JSON health document, which in Spring Boot and health+json is the overall one. A regular
     * expression keeps the tool free of a JSON library.
     */
    static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]{1,32})\"");

    public record Health(
            @Param(description = "True when the endpoint answered 2xx and, if expected, reported the expected status") boolean up,
            @Param(description = "The path checked") String path,
            @Param(description = "HTTP status, or 0 when there was no answer") int httpStatus,
            @Param(description = "Time to the answer, milliseconds") long latencyMs,
            @Param(description = "The status the service reports about itself, such as UP, or empty") String reportedStatus,
            @Param(description = "Why it is down, such as UPSTREAM_TIMEOUT or HTTP 503, or empty when up") String reason,
            @Param(description = "When it was checked, ISO 8601") String checkedAt) {}

    @ServerTool(name = "check", title = "Check an endpoint",
            description = "Calls the service's health endpoint and reports whether it is up, its HTTP status, latency"
                    + " and self-reported status.",
            useWhen = "Use it for 'is the orders API up?' or to confirm a service recovered after a restart.",
            effect = EffectSafety.READ_ONLY, routes = {"health.one", "health.two"})
    @Example(arguments = "{\"path\":\"actuator/health\",\"expectStatus\":\"UP\"}", exchanges = "exchanges/actuator-up.json")
    @Example(arguments = "{\"path\":\"../admin\"}", expectedError = "INVALID_ARGUMENT")
    public Health check(
            @Param(description = "Health path, one or two segments, such as health, healthz or actuator/health",
                    defaultValue = "health", maxLength = 129) String path,
            @Param(description = "Status the service must report to count as up, such as UP; empty accepts any 2xx",
                    required = false, maxLength = 32) String expectStatus,
            @Param(description = "Give up after this many milliseconds", defaultValue = "5000", min = "100", max = "10000")
            int timeoutMs,
            ToolContext context) {
        String[] parts = path.replaceFirst("^/", "").split("/", -1);
        for (String p : parts) {
            if (!SEGMENT.matcher(p).matches() || p.matches("\\.+")) throw new ToolInputError("path must be one or two plain segments");
        }
        if (parts.length > 2) throw new ToolInputError("path must be one or two plain segments");
        RouteCall call = parts.length == 1
                ? context.http().route("health.one").pathVar("a", parts[0])
                : context.http().route("health.two").pathVar("a", parts[0]).pathVar("b", parts[1]);
        String shown = "/" + String.join("/", parts);
        long started = System.nanoTime();
        try {
            RawResponse response = call.timeout(Duration.ofMillis(timeoutMs)).sendRaw();
            long latency = elapsed(started);
            String reported = reportedStatus(response.body());
            boolean expected = expectStatus == null || expectStatus.isBlank() || expectStatus.equalsIgnoreCase(reported);
            return new Health(expected, shown, response.status(), latency, reported,
                    expected ? "" : "reported " + (reported.isEmpty() ? "no status" : reported) + ", expected " + expectStatus,
                    Instant.now().toString());
        } catch (UpstreamError e) {
            int status = e.status().orElse(0);
            String reported = e.body().map(b -> reportedStatus(b.getBytes(StandardCharsets.UTF_8))).orElse("");
            return new Health(false, shown, status, elapsed(started), reported,
                    status > 0 ? "HTTP " + status : e.code(), Instant.now().toString());
        }
    }

    /** {"status":"UP"} (Spring Boot), {"status":"pass"} (health+json), or a short plain-text body such as "ok". */
    static String reportedStatus(byte[] body) {
        if (body == null || body.length == 0) return "";
        String text = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            Matcher m = STATUS.matcher(text);
            return m.find() ? m.group(1) : "";
        }
        return text.length() <= 32 && !text.contains("\n") ? text : "";
    }

    static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
