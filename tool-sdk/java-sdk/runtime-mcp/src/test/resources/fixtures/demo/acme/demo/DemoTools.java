// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.demo;

import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.JwtClaim;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ToolVariables;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.annotations.Variable;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import io.ololabs.toolgate.sdk.errors.ToolRetryableError;
import io.ololabs.toolgate.sdk.errors.UpstreamError;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Tools the runtime tests call. */
@ToolPackage(id = "acme.demo", name = "Demo", publisher = "Acme")
@Global(name = "Limit", type = ValueType.INT, defaultValue = "5", min = "1")
public class DemoTools {
    public enum Mood { HAPPY, SAD }

    public record Line(String text, int count, Optional<LocalDate> on, Mood mood, List<String> tags) {}

    public record Strict(@Param(description = "", pattern = "^x$") String v) {}

    @ServerTool(name = "demo.echo", title = "Echo", description = "Echoes text.", effect = EffectSafety.READ_ONLY)
    public Line echo(@Param(description = "Text", minLength = 1) String text,
            @Param(description = "Times", min = "1", max = "10", defaultValue = "1") int count,
            @Param(description = "Day", required = false) LocalDate on,
            @Param(description = "Mood") Mood mood,
            @Param(description = "Tags", required = false) List<String> tags) {
        return new Line(text.repeat(count), count, Optional.ofNullable(on), mood, tags == null ? List.of() : tags);
    }

    @ServerTool(name = "demo.fail", title = "Fail", description = "Raises the error it is asked for.", effect = EffectSafety.READ_ONLY,
            errors = {
                @ErrorDecl(code = "NOT_FOUND", description = "No such thing", dataSchema = "{\"type\":\"object\",\"required\":[\"id\"]}"),
                @ErrorDecl(code = "BUSY", description = "Try later", retryable = true)
            })
    public Map<String, Object> fail(@Param(description = "Kind") String kind, ToolContext context) {
        switch (kind) {
            case "input" -> throw new ToolInputError("The id is malformed");
            case "business" -> throw new ToolBusinessError("NOT_FOUND", "Order 123456 was not found.", Map.of("id", "123456"));
            case "bad-data" -> throw new ToolBusinessError("NOT_FOUND", "Missing data", Map.of("other", 1));
            case "undeclared" -> throw new ToolBusinessError("SURPRISE", "Something odd");
            case "retryable" -> throw new ToolRetryableError("BUSY", "Busy now");
            case "upstream" -> throw new UpstreamError("UPSTREAM_5XX", "orders.get", 503, "req-1", false, null);
            case "config" -> throw new ToolConfigurationError("Not configured");
            case "platform" -> throw new PlatformError(PlatformError.Code.ROUTE_REFUSED, "Refused");
            case "null" -> {
                String s = null;
                return Map.of("length", s.length());
            }
            case "long" -> throw new ToolBusinessError("NOT_FOUND", "x".repeat(4000), Map.of("id", "y".repeat(20_000)));
            case "undeclared-global" -> {
                return Map.of("v", context.global().getStringValue("Nope"));
            }
            case "wrong-type" -> {
                return Map.of("v", context.global().getStringValue("Limit"));
            }
            default -> {
                return Map.of("kind", kind);
            }
        }
    }

    @ServerTool(name = "demo.write", title = "Write", description = "Not idempotent.", effect = EffectSafety.NON_IDEMPOTENT,
            errors = @ErrorDecl(code = "BUSY", description = "Try later", retryable = true))
    public Map<String, Object> write() {
        throw new ToolRetryableError("BUSY", "Busy now");
    }

    @ServerTool(name = "demo.slow", title = "Slow", description = "Sleeps.", effect = EffectSafety.READ_ONLY, maxConcurrency = 1,
            maxDurationMs = 3000)
    public CompletionStage<Map<String, Object>> slow(@Param(description = "Milliseconds") long ms, ToolContext context) {
        String id = context.invocation().invocationId();
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return CompletableFuture.completedFuture(Map.of("slept", ms, "invocationId", id, "cancelled", context.isCancelled()));
    }

    @ServerTool(name = "demo.secret", title = "Secret", description = "Leaks on purpose.", effect = EffectSafety.READ_ONLY)
    @Secret(name = "ApiKey")
    public Map<String, Object> secret(@Param(description = "Token", sensitive = true) String token, ToolContext context) {
        String key = context.secret().getStringValue("ApiKey");
        context.log().info("key " + key + " token " + token);
        if (token.equals("fail")) throw new ToolBusinessError("SURPRISE", "key " + key + " and token " + token);
        return Map.of("limit", context.global().getIntValue("Limit"));
    }

    @ServerTool(name = "demo.grouped", title = "Grouped", description = "Grouped inputs.", effect = EffectSafety.READ_ONLY)
    @ToolVariables(inputs = {
        @Variable(name = "name", description = "Name", type = ValueType.STRING),
        @Variable(name = "size", description = "Size", type = ValueType.LONG, required = false, defaultValue = "7")
    })
    public Map<String, Object> grouped(ToolContext context) {
        return Map.of("name", context.getStringValue("name"), "size", context.getLongValue("size"), "tool", context.invocation().toolId(),
                "mode", context.invocation().mode().name());
    }

    @ServerTool(name = "demo.strict", title = "Strict", description = "Breaks its output schema.", effect = EffectSafety.READ_ONLY)
    public Strict strict(@Param(description = "Value") String v) {
        return new Strict(v);
    }

    @ServerTool(name = "demo.big", title = "Big", description = "Returns too much.", effect = EffectSafety.READ_ONLY)
    public Map<String, Object> big(@Param(description = "Bytes") int bytes) {
        return Map.of("data", "z".repeat(bytes));
    }

    @ServerTool(name = "demo.export", title = "Export", description = "A task.", effect = EffectSafety.READ_ONLY, async = true)
    public Map<String, Object> export() {
        return Map.of();
    }

    @ServerTool(name = "demo.whoami", title = "Who am I", description = "Reads a claim.", effect = EffectSafety.READ_ONLY)
    @JwtClaim(name = "UserName", claim = "preferred_username")
    public Map<String, Object> whoami(ToolContext context) {
        return Map.of("user", context.jwt().getStringValue("UserName"), "subject", context.jwt().getSubject());
    }
}
