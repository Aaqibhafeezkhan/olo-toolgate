// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ControlTool;
import io.ololabs.toolgate.contracts.ControlWorkloadBinding;
import io.ololabs.toolgate.contracts.v2.CredentialKind;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.List;

/** Rechecks run scope before handing an MCP request to the existing Gateway authorization path. */
public final class WorkflowToolAuthority {
    private final Codec codec;
    private final WorkflowModelSelection selection;
    private final Clock clock;

    public WorkflowToolAuthority(Codec codec, WorkflowModelSelection selection, Clock clock) {
        this.codec = codec;
        this.selection = selection;
        this.clock = clock;
    }

    public record ToolContext(RequestContext context, long expiresAtUnixMs, List<String> readOnlyTools) {}

    public ToolContext resolve(Store.Session tx, Ids.TenantId tenant, String runId, String token, String toolId, String requestId) {
        var runtime = selection.context(tx, tenant, runId, token);
        var run = runtime.run();
        String agentId = runtime.step().subjectId();
        if (!runtime.step().kind().equals("AGENT")) throw ManagementAccess.denied();
        var directory = tx.load();
        long now = clock.millis();
        var bindings = directory.entries().values().stream()
            .filter(entry -> entry.id().kind() == Ids.Kind.WORKLOAD_BINDING && entry.enabled())
            .map(entry -> codec.model(entry.document(), ControlWorkloadBinding.class))
            .filter(binding -> binding.agentId().equals(agentId) && binding.mode() == RequestMode.SERVICE
                && binding.parentBindingId() == null && !Boolean.TRUE.equals(binding.needsReauthorization())
                && binding.expiresAtUnixMs() > now).toList();
        if (bindings.size() != 1) throw ManagementAccess.denied();
        var binding = bindings.getFirst();
        var execution = DirectoryService.executionBinding(codec, directory, agentId);
        if (execution == null) throw ManagementAccess.denied();
        long expires = Math.min(binding.expiresAtUnixMs(), tx.workflowRuns().activeLease(runId).expiresAt());
        boolean readOnly = toolId != null && readOnly(tx, toolId);
        if (toolId != null && !readOnly && "PREPARE".equals(run.remediationMode())) throw ManagementAccess.denied();
        var readOnlyTools = toolId != null ? List.<String>of() : directory.entries().values().stream()
            .filter(entry -> entry.id().kind() == Ids.Kind.TOOL && entry.enabled()).map(entry -> entry.id().value())
            .sorted().limit(256).filter(id -> readOnly(tx, id)).limit(64).toList();
        return new ToolContext(new RequestContext(requestId, tenant.value(), RequestMode.SERVICE, CredentialKind.STATIC_TOKEN,
            null, null, agentId, binding.id(), List.of(), null, binding.credentialEpoch(), null, expires,
            execution, binding.credentialSha256()), expires, readOnlyTools);
    }

    private boolean readOnly(Store.Session tx, String id) {
        var entry = tx.load().entries().get(Ids.Kind.TOOL.id(id));
        if (entry == null || !entry.enabled()) throw ManagementAccess.denied();
        var tool = codec.model(entry.document(), ControlTool.class);
        String digest = tool.packageDigest().startsWith("sha256:") ? tool.packageDigest() : "sha256:" + tool.packageDigest();
        var version = tx.packages().version(digest);
        if (version == null) {
            String evidence = tx.packages().document(ClientReleases.effectEvidenceKey(codec, id, tool.packageDigest()));
            if (evidence == null) return false;
            var descriptor = (JsonNode) codec.value(evidence);
            return id.equals(descriptor.path("toolId").asText()) && "READ_ONLY".equals(descriptor.path("safety").asText());
        }
        var manifest = ((JsonNode) codec.value(version.document())).path("manifest");
        for (var item : manifest.path("tools")) {
            var descriptor = item.path("descriptor");
            if (id.equals(descriptor.path("toolId").asText())) return "READ_ONLY".equals(descriptor.path("effect").path("safety").asText());
        }
        return false;
    }
}
