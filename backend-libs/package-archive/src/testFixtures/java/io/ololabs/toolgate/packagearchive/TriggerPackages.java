// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds `.tgpkg` packages with trigger exports the way {@code toolgate pack} writes them: canonical manifest and
 * descriptors, {@code triggerDescriptorDigest}, {@code descriptorSetDigest} over the exports, a canonical
 * {@code CHECKSUMS.sha256} and a stored zip. For tests only.
 */
public final class TriggerPackages {
    private TriggerPackages() {}

    /** The client POLL descriptor of the {@code inbox-watch} sample, for package {@code packageId}. */
    public static String clientDescriptor(String packageId, String name) {
        return """
                {"checkpointSchemaVersion":1,"configSchema":{"additionalProperties":false,"properties":{"extension":{"default":".pdf","description":"File name suffix to report","pattern":"^\\\\.[a-z0-9]{1,16}$","type":"string"},"folder":{"description":"Folder to watch","pattern":"^[a-z0-9][a-z0-9._ -]{0,127}$","type":"string"}},"required":["folder"],"type":"object"},"eventSchema":{"additionalProperties":false,"properties":{"name":{"type":"string"},"path":{"type":"string"},"size":{"maximum":9007199254740991,"minimum":-9007199254740991,"type":"integer"}},"required":["name","path","size"],"type":"object"},"eventType":"examples.files.observed.v1","executors":["CLIENT"],"kind":"CLIENT_TRIGGER","limits":{"maxBrokerCallsPerMinute":120,"maxCheckpointBytes":16384,"maxEventBytes":16384,"maxEventsPerHour":600,"memoryMiB":256,"stopGraceMs":10000},"lossTolerant":false,"mode":"POLL","onTerminalRejection":"HALT","overflow":"BLOCK","permissions":["DEVICE_FILES_READ"],"routes":[],"schedule":{"intervalMs":60000},"secrets":[],"selfTests":[],"sourceReplay":"STATE_SNAPSHOT","tcpDestinations":[],"tiers":["OCI_SANDBOX"],"triggerId":"%s/%s"}"""
                .formatted(packageId, name).strip();
    }

    /** A package of trigger exports only, one Java artifact, with each descriptor given as JSON text. */
    public static byte[] build(String packageId, String version, List<String> descriptors) {
        return build(packageId, version, descriptors, Map.of());
    }

    /** As {@link #build(String, String, List)}, with extra files placed into the archive and its checksums (for tampering tests). */
    public static byte[] build(String packageId, String version, List<String> descriptors, Map<String, byte[]> extra) {
        byte[] artifact = ("PK tiny jar for " + packageId + " " + version + "\n").getBytes(StandardCharsets.UTF_8);
        var files = new TreeMap<String, byte[]>();
        files.put("artifacts/tool.jar", artifact);
        var triggers = new ArrayList<Json>();
        var set = new TreeMap<String, Json>();
        for (String text : descriptors) {
            Json descriptor = Json.parse(text);
            byte[] canonical = Canonical.bytes(descriptor);
            String triggerId = descriptor.path("triggerId").asText();
            String digest = Canonical.sha256(canonical);
            files.put("triggers/" + triggerId.substring(triggerId.indexOf('/') + 1) + ".json", canonical);
            var entry = new LinkedHashMap<String, Json>();
            entry.put("descriptor", descriptor);
            entry.put("triggerDescriptorDigest", Json.string(digest));
            entry.put("runtimeId", Json.string("jvm"));
            entry.put("entryPoint", Json.string("io.ololabs.toolgate.runtime.Main"));
            entry.put("artifactIds", Json.array(List.of(Json.string("tool"))));
            triggers.add(Json.object(entry));
            var item = new LinkedHashMap<String, Json>();
            item.put("triggerId", Json.string(triggerId));
            item.put("triggerDescriptorDigest", Json.string(digest));
            set.put(triggerId, Json.object(item));
        }
        String manifest = """
                {"schemaVersion":2,"kind":"SDK","id":"%s","version":"%s","publisher":"Acme",
                 "sdk":{"language":"JAVA","version":"1.0.0","protocol":{"min":2,"max":2},"descriptorGeneration":1},
                 "compatibility":{"contractsVersion":"1.0.0"},
                 "runtimes":[{"runtimeId":"jvm","language":"java","version":"17"}],
                 "tools":[],
                 "artifacts":[{"artifactId":"tool","path":"artifacts/tool.jar","mediaType":"application/java-archive","runtimeId":"jvm","artifactDigest":"%s","sizeBytes":%d}],
                 "credentialReferences":[],"connections":[],"composition":[],
                 "limits":{"maxTools":64,"maxDescriptorBytes":1048576}}""".formatted(packageId, version, Canonical.sha256(artifact), artifact.length);
        var members = new LinkedHashMap<String, Json>();
        Json parsed = Json.parse(manifest);
        for (String field : parsed.fieldNames()) members.put(field, parsed.get(field));
        members.put("triggers", Json.array(triggers));
        members.put("descriptorSetDigest", Json.string(Canonical.digest(Json.array(new ArrayList<>(set.values())))));
        byte[] manifestBytes = (new String(Canonical.bytes(Json.object(members)), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        files.put(PackageArchive.MANIFEST, manifestBytes);
        files.putAll(extra);
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put(PackageArchive.CHECKSUMS, checksums.toString().getBytes(StandardCharsets.UTF_8));
        return zip(files);
    }

    private static byte[] zip(Map<String, byte[]> files) {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : new TreeMap<>(files).entrySet()) {
                var entry = new ZipEntry(e.getKey());
                var crc = new CRC32();
                crc.update(e.getValue());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(e.getValue().length);
                entry.setCompressedSize(e.getValue().length);
                entry.setCrc(crc.getValue());
                entry.setTime(315532800000L);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
