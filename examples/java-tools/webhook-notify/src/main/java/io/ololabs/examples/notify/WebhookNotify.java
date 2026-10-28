// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.notify;

import io.ololabs.toolgate.sdk.annotations.AuthType;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.HttpAuth;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Idempotency;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.RawResponse;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends an alert or notification to a JSON webhook: an internal alert relay, n8n, an incident tool or a chat bridge.
 *
 * <p>Shows the parts of the SDK that matter for anything with a side effect: the API key is route auth, so under
 * ToolGate the broker adds it and the code never sees it; the tool is {@code NON_IDEMPOTENT} with a business key
 * ({@code reference}), so a retried call with the same reference returns the first result instead of alerting twice,
 * and the key is forwarded as {@code Idempotency-Key} so the receiver can deduplicate too; and the channels a model may
 * post to are an administrator's allowlist. Change the route path to match your receiver.
 */
@ToolPackage(id = "examples.notify", name = "Webhook notify", publisher = "ToolGate Examples",
        description = "Sends alerts and notifications to an approved JSON webhook, at most once per reference.")
@Destination(id = "webhook", hostSetting = "WebhookHost")
@HttpAuth(id = "webhook-key", type = AuthType.API_KEY, header = "X-Api-Key", secret = "WebhookApiKey")
@HttpRoute(id = "hook.post", destination = "webhook", method = HttpMethod.POST,
        path = "/hooks/{channel:[A-Za-z0-9_-]{1,64}}", auth = "webhook-key", headers = "Idempotency-Key",
        maxRequestBytes = 16_384, maxResponseBytes = 65_536, timeoutMs = 10_000)
@Secret(name = "WebhookApiKey")
@Global(name = "AllowedChannels", type = ValueType.STRING, defaultValue = "ops-alerts",
        description = "Comma-separated channels callers may post to.")
@Global(name = "Source", type = ValueType.STRING, defaultValue = "toolgate",
        description = "Sender name included in every message, such as the environment or team.")
public class WebhookNotify {
    public record Sent(
            @Param(description = "Channel posted to") String channel,
            @Param(description = "The caller's reference, used to send at most once") String reference,
            @Param(description = "HTTP status the receiver answered") int httpStatus,
            @Param(description = "Id the receiver gave the message, when it returned one") String receiptId,
            @Param(description = "When it was sent, ISO 8601") String sentAt) {}

    @ServerTool(name = "send", title = "Send a notification",
            description = "Posts a notification (title, text, severity) to an allowlisted webhook channel. Sending again"
                    + " with the same reference returns the first result instead of sending twice.",
            useWhen = "Use it to raise an alert or tell a team about something the user asked to share.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference", routes = "hook.post",
            errors = @ErrorDecl(code = "CHANNEL_NOT_ALLOWED", description = "The channel is not in AllowedChannels."), reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @Idempotency(header = "Idempotency-Key")
    @Example(arguments = "{\"channel\":\"ops-alerts\",\"title\":\"Disk almost full\",\"text\":\"/var is 93% used on app-1\","
            + "\"severity\":\"warning\",\"reference\":\"disk-app-1-2026-10-12\"}",
            exchanges = "exchanges/sent.json")
    @Example(arguments = "{\"channel\":\"ceo-inbox\",\"title\":\"hi\",\"text\":\"hi\",\"reference\":\"r1\"}",
            expectedError = "CHANNEL_NOT_ALLOWED")
    public Sent send(
            @Param(description = "Channel to post to, such as ops-alerts", maxLength = 64) String channel,
            @Param(description = "Short headline", minLength = 1, maxLength = 200) String title,
            @Param(description = "Message body", minLength = 1, maxLength = 4000) String text,
            @Param(description = "How urgent it is", defaultValue = "info", enumValues = {"info", "warning", "critical"})
            String severity,
            @Param(description = "Your unique id for this notification, such as an incident or job id. A repeat with the"
                    + " same reference is not sent again.", minLength = 1, maxLength = 128) String reference,
            ToolContext context) {
        boolean allowed = Arrays.stream(context.global().getStringValue("AllowedChannels").split(","))
                .map(String::trim).anyMatch(channel::equals);
        if (!allowed) throw new ToolBusinessError("CHANNEL_NOT_ALLOWED", "Channel " + channel + " is not allowed.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("text", text);
        body.put("severity", severity);
        body.put("source", context.global().getStringValue("Source"));
        body.put("reference", reference);
        RawResponse response = context.http().post("webhook", "/hooks/{channel}").pathVar("channel", channel).body(body).sendRaw();
        return new Sent(channel, reference, response.status(), receiptId(response.body()), Instant.now().toString());
    }

    /** An "id" in a JSON answer, such as {"id":"msg-123"}; empty otherwise. */
    static String receiptId(byte[] body) {
        if (body == null) return "";
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?([A-Za-z0-9._:-]{1,128})")
                .matcher(new String(body, 0, Math.min(body.length, 4096), java.nio.charset.StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "";
    }
}
