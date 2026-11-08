// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.ControlAgentGroup;
import io.ololabs.toolgate.contracts.ControlTeam;
import io.ololabs.toolgate.contracts.v2.TriggerClassification;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;

/**
 * Information flow, coarse mode (plan 12.2 and 12.3; triggers module 11). Labels are computed only here, in Control.
 *
 * <p>Coarse mode: an event type carries one label, the highest {@code x-toolgate-classification} anywhere in its signed
 * event schema, joined with the tenant's fail-closed default for its integration kind (unlabelled data is never assumed
 * lower). A reader whose clearance covers the label sees the event; any other reader sees only the envelope ({@code id},
 * {@code type}, {@code source}, {@code time}) and never {@code data} or {@code subject}, which may be derived from data.
 * Field-level labelling (module 16) is off.
 */
public final class TriggerLabels {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final TriggerClassification DEFAULT_CLEARANCE = TriggerClassification.INTERNAL;
    private TriggerLabels() {}

    public static TriggerClassification join(TriggerClassification a, TriggerClassification b) { return a.ordinal() >= b.ordinal() ? a : b; }
    public static boolean covers(TriggerClassification clearance, TriggerClassification label) { return clearance.ordinal() >= label.ordinal(); }

    /** Integration kinds of plan 12.3, of which client triggers from SDK packages use {@code sdkPackage}. */
    public enum Integration { SDK_PACKAGE, MCP_PROXY, EXTERNAL, UNKNOWN }

    /**
     * The coarse label of a trigger's events. A trigger that reaches external systems (routes or TCP destinations) is
     * an external integration; any other SDK package trigger is an SDK package integration.
     */
    public static TriggerClassification eventLabel(JsonNode descriptor, JsonNode settings) {
        boolean external = descriptor.path("routes").size() > 0 || descriptor.path("tcpDestinations").size() > 0;
        var integration = descriptor.isMissingNode() || descriptor.isNull() ? Integration.UNKNOWN : external ? Integration.EXTERNAL : Integration.SDK_PACKAGE;
        var label = fallback(settings, integration);
        return join(label, declared(descriptor.path("eventSchema"), TriggerClassification.PUBLIC, 0));
    }

    /** The tenant default for unlabelled data of an integration kind (plan 12.3 shipped defaults when unset). */
    public static TriggerClassification fallback(JsonNode settings, Integration integration) {
        String field = switch (integration) { case SDK_PACKAGE -> "sdkPackage"; case MCP_PROXY -> "mcpProxy"; case EXTERNAL -> "external"; case UNKNOWN -> "unknown"; };
        var configured = settings == null ? null : settings.path("defaultClassification").path(field);
        if (configured != null && configured.isTextual()) return TriggerClassification.valueOf(configured.asText());
        return shipped(integration);
    }

    public static TriggerClassification shipped(Integration integration) {
        return switch (integration) {
            case SDK_PACKAGE -> TriggerClassification.INTERNAL;
            case MCP_PROXY, EXTERNAL -> TriggerClassification.CONFIDENTIAL;
            case UNKNOWN -> TriggerClassification.RESTRICTED;
        };
    }

    /** The highest declared label in a schema, over every branch and subschema (never only a matched branch). */
    static TriggerClassification declared(JsonNode schema, TriggerClassification label, int depth) {
        if (depth > 64 || schema == null || !schema.isContainerNode()) return label;
        var keyword = schema.path("x-toolgate-classification");
        if (keyword.isTextual()) {
            try { label = join(label, TriggerClassification.valueOf(keyword.asText())); }
            catch (IllegalArgumentException unknown) { label = TriggerClassification.RESTRICTED; } // fail closed
        }
        for (var child : schema) label = declared(child, label, depth + 1);
        return label;
    }

    /**
     * A principal's clearance (plan 12.2): the highest {@code dataClearance} of the enabled Teams (users) or Agent Groups
     * (agents) it belongs to, and {@code INTERNAL} when none sets one.
     */
    public static TriggerClassification clearance(Directory directory, Codec codec, String userId, String agentId) {
        TriggerClassification best = null;
        for (var entry : directory.entries().values()) {
            if (!entry.enabled()) continue;
            TriggerClassification declared = null;
            if (userId != null && entry.id().kind() == Kind.TEAM) {
                var team = codec.model(entry.document(), ControlTeam.class);
                if (GroupGraph.members(entry, codec).contains(userId) && team.dataClearance() != null) declared = TriggerClassification.valueOf(team.dataClearance().name());
            } else if (agentId != null && entry.id().kind() == Kind.AGENT_GROUP) {
                var group = codec.model(entry.document(), ControlAgentGroup.class);
                if (group.agentIds().contains(agentId) && group.dataClearance() != null) declared = TriggerClassification.valueOf(group.dataClearance().name());
            }
            if (declared != null) best = best == null ? declared : join(best, declared);
        }
        return best == null ? DEFAULT_CLEARANCE : best;
    }

    /** The reader's view of a stored CloudEvent (TriggerViewEvent): data and subject only when readable. */
    public static ObjectNode view(JsonNode event, TriggerClassification label, TriggerClassification clearance) {
        var view = JSON.createObjectNode();
        view.put("specversion", "1.0").put("id", event.path("id").asText()).put("source", event.path("source").asText()).put("type", event.path("type").asText());
        if (event.hasNonNull("time")) view.put("time", event.path("time").asText());
        boolean readable = covers(clearance, label);
        if (readable) {
            if (event.hasNonNull("subject")) view.put("subject", event.path("subject").asText());
            view.set("data", event.path("data").deepCopy());
        }
        view.put("tgclassification", label.name()).put("tgredacted", !readable);
        return view;
    }
}
