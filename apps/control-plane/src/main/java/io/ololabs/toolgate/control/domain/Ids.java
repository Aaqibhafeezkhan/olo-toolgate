// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.domain;

/** Typed directory identifiers; tenant identity always comes from verified authentication. */
public final class Ids {
    private Ids() {}
    public static String valid(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid identifier");
        }
        return value;
    }
    public record TenantId(String value) {
        public TenantId {
            if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("Canonical tenant UUID required");
        }
        public static TenantId create() { return new TenantId(java.util.UUID.randomUUID().toString()); }
    }
    public sealed interface RecordId permits UserId, TeamId, AgentId, ToolId, PolicyId, DeviceId, RoleId, DeviceGroupId, GroupRecordId {
        String value();
        Kind kind();
    }
    public record UserId(String value) implements RecordId {
        public UserId { valid(value); } public Kind kind() { return Kind.USER; }
    }
    public record TeamId(String value) implements RecordId {
        public TeamId { valid(value); } public Kind kind() { return Kind.TEAM; }
    }
    public record AgentId(String value) implements RecordId {
        public AgentId { valid(value); } public Kind kind() { return Kind.AGENT; }
    }
    public record ToolId(String value) implements RecordId {
        public ToolId { valid(value); } public Kind kind() { return Kind.TOOL; }
    }
    public record PolicyId(String value) implements RecordId {
        public PolicyId { valid(value); } public Kind kind() { return Kind.POLICY; }
    }
    public record DeviceId(String value) implements RecordId {
        public DeviceId { valid(value); } public Kind kind() { return Kind.DEVICE; }
    }
    public record RoleId(String value) implements RecordId {
        public RoleId { valid(value); } public Kind kind() { return Kind.ROLE; }
    }
    public record DeviceGroupId(String value) implements RecordId {
        public DeviceGroupId { valid(value); } public Kind kind() { return Kind.DEVICE_GROUP; }
    }
    public record GroupRecordId(Kind kind, String value) implements RecordId {
        public GroupRecordId { java.util.Objects.requireNonNull(kind); valid(value); }
    }
    public enum Kind {
        USER("users", "ControlUser"), TEAM("teams", "ControlTeam"),
        AGENT("agents", "ControlAgent"), TOOL("tools", "ControlTool"),
        POLICY("policies", "ControlPolicy"), DEVICE("devices", "ControlDevice"), ROLE("roles", "ControlRole"), DEVICE_GROUP("device-groups", "ControlDeviceGroup"),
        AGENT_GROUP("agent-groups", "ControlAgentGroup"), TOOL_GROUP("tool-groups", "ControlToolGroup"),
        GRANT("grants", "ControlAccessGrant"), DELEGATION("delegations", "ControlDelegation"),
        AGENT_DELEGATION("agent-delegations", "ControlAgentDelegation"), BINDING("bindings", "ControlExecutionBinding"),
        EXTRACTOR("extractors", "ControlResourceExtractor"), WORKLOAD_BINDING("workload-bindings", "ControlWorkloadBinding"),
        IDENTITY_BINDING("identity-bindings", "ControlIdentityBinding"), DEVICE_EVIDENCE("device-evidence", "ControlDeviceEvidence");
        private final String path;
        private final String model;
        Kind(String path, String model) { this.path = path; this.model = model; }
        public String path() { return path; }
        public String model() { return model; }
        public String snapshotKey() {
            var words = path.split("-"); var value = new StringBuilder(words[0]);
            for (int i=1;i<words.length;i++) value.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
            return value.toString();
        }
        public RecordId id(String value) {
            return switch (this) {
                case USER -> new UserId(value); case TEAM -> new TeamId(value);
                case AGENT -> new AgentId(value); case TOOL -> new ToolId(value);
                case POLICY -> new PolicyId(value); case DEVICE -> new DeviceId(value); case ROLE -> new RoleId(value);
                case DEVICE_GROUP -> new DeviceGroupId(value);
                default -> new GroupRecordId(this, value);
            };
        }
        public static Kind path(String value) {
            for (var kind : values()) if (kind.path.equals(value)) return kind;
            throw new IllegalArgumentException("Unknown record kind");
        }
    }
}
