// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.McpToolApproval;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The package of one MCP server approval (ADR 030): kind MCP_PROXY, id mcp.<slug>, version
 *  1.0.<approval sequence>, the Tool Host's native mcp-proxy runtime, and one artifact, mcp-proxy.json, that every tool
 *  runs from. Each approved upstream tool becomes a SERVER descriptor; the upstream credential is named by the proxy
 *  document, so the package digest pins the URL, the credential reference, the TLS pins and every definition digest. */
final class McpPackages {
    private McpPackages() {}
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final String RUNTIME = PackageCompatibility.MCP_PROXY_RUNTIME, ARTIFACT = "artifacts/mcp-proxy.json";

    static byte[] generate(String slug, long sequence, JsonNode snapshot, Map<String, JsonNode> definitions, List<McpToolApproval> tools, ObjectNode proxy) {
        String packageId = "mcp." + slug;
        var files = new TreeMap<String, byte[]>();
        byte[] document = Canonical.jcs(proxy);
        files.put(ARTIFACT, document);
        var manifest = JSON.objectNode().put("schemaVersion", 2).put("kind", "MCP_PROXY").put("id", packageId).put("version", "1.0." + sequence).put("publisher", "ToolGate");
        manifest.putObject("compatibility").put("contractsVersion", io.ololabs.toolgate.contracts.ContractSet.VERSION);
        manifest.putArray("runtimes").addObject().put("runtimeId", RUNTIME).put("language", RUNTIME).put("version", Long.toString(ServerPackages.PROTOCOL));
        var entries = manifest.putArray("tools"); var set = new TreeMap<String, String>();
        for (var approval : tools) {
            var upstream = definitions.get(approval.upstreamName()).get("tool");
            var descriptor = descriptor(slug, approval.name(), upstream);
            byte[] canonical = Canonical.bytes(descriptor); String digest = Canonical.sha256(canonical);
            files.put("tools/" + approval.name() + ".json", canonical);
            var entry = entries.addObject(); entry.set("descriptor", descriptor);
            entry.put("toolDescriptorDigest", digest).put("runtimeId", RUNTIME); entry.putArray("artifactIds").add("mcp-proxy");
            set.put(descriptor.get("toolId").asText(), digest);
        }
        var digests = JSON.arrayNode(); set.forEach((id, digest) -> digests.addObject().put("toolId", id).put("toolDescriptorDigest", digest)); // TreeMap: UTF-16 order
        manifest.put("descriptorSetDigest", Canonical.digest(digests));
        manifest.putArray("artifacts").addObject().put("artifactId", "mcp-proxy").put("path", ARTIFACT).put("mediaType", ServerPackages.MCP_PROXY_MEDIA_TYPE)
            .put("runtimeId", RUNTIME).put("artifactDigest", Canonical.sha256(document)).put("sizeBytes", document.length);
        manifest.putArray("credentialReferences"); manifest.putArray("connections"); manifest.putArray("composition");
        manifest.putObject("limits").put("maxTools", 64).put("maxDescriptorBytes", 1048576);
        files.put(PackageArchive.MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put(PackageArchive.CHECKSUMS, checksums.toString().getBytes(StandardCharsets.UTF_8));
        return zip(files);
    }

    /** A SERVER descriptor for one approved upstream tool. Upstream annotations are the hints an administrator approved. */
    static ObjectNode descriptor(String slug, String name, JsonNode upstream) {
        var annotations = upstream.path("annotations");
        boolean readOnly = annotations.path("readOnlyHint").asBoolean(false), idempotent = annotations.path("idempotentHint").asBoolean(false);
        var descriptor = JSON.objectNode().put("descriptorGeneration", 1).put("toolId", "mcp." + slug + "/" + name).put("mcpName", "mcp." + slug + "." + name)
            .put("kind", "SERVER").put("title", clip(upstream.path("title").asText(upstream.path("annotations").path("title").asText(name)), 100))
            .put("description", clip(upstream.path("description").asText(name), 2048));
        descriptor.set("inputSchema", upstream.get("inputSchema"));
        if (upstream.has("outputSchema")) descriptor.set("outputSchema", upstream.get("outputSchema"));
        descriptor.putArray("errors");
        descriptor.putObject("effect").put("safety", readOnly ? "READ_ONLY" : idempotent ? "IDEMPOTENT" : "UNKNOWN")
            .put("destructive", !readOnly && annotations.path("destructiveHint").asBoolean(true)).put("openWorld", annotations.path("openWorldHint").asBoolean(true));
        descriptor.putObject("businessKey").put("required", false).put("namespace", "TENANT");
        descriptor.putObject("execution").put("async", false).put("maxDurationMs", 60_000).put("stateless", true).put("cancelIfAbandoned", true);
        for (var field : List.of("routes", "destinations", "httpAuth", "tcp")) descriptor.putArray(field);
        var variables = descriptor.putObject("variables"); variables.putArray("globals"); variables.putArray("secrets"); variables.putArray("claims");
        descriptor.putArray("permissions"); descriptor.putArray("toolsets");
        return descriptor;
    }
    private static String clip(String value, int max) {
        String text = value.isBlank() ? "-" : value;
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static byte[] zip(SortedMap<String, byte[]> files) {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : files.entrySet()) {
                var entry = new ZipEntry(e.getKey()); entry.setMethod(ZipEntry.STORED); entry.setSize(e.getValue().length); entry.setTime(0);
                var crc = new CRC32(); crc.update(e.getValue()); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(e.getValue()); zip.closeEntry();
            }
        } catch (java.io.IOException failure) { throw Failure.unavailable(); }
        return out.toByteArray();
    }
}
