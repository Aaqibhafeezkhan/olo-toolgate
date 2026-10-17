// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.application.PackageCompatibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Marketplace design §6.2 A: each way a package can be one this ToolGate cannot run. */
final class PackageCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    private static ObjectNode manifest() throws Exception { return (ObjectNode) JSON.readTree(ServerPackagesTest.files().get("toolgate-package.json")); }
    private static ObjectNode edited(Consumer<ObjectNode> edit) throws Exception { var manifest = manifest(); edit.accept(manifest); return manifest; }
    private static JsonNode host(String version, int min, int max) throws Exception {
        return JSON.readTree("{\"version\":\"" + version + "\",\"protocol\":{\"min\":" + min + ",\"max\":" + max + "}}");
    }

    /** The package with its manifest edited, its checksums recomputed: structurally valid, only the declaration differs. */
    static Map<String, byte[]> files(Consumer<ObjectNode> edit) throws Exception {
        var files = new TreeMap<>(ServerPackagesTest.files());
        var manifest = (ObjectNode) JSON.readTree(files.get("toolgate-package.json")); edit.accept(manifest);
        files.put("toolgate-package.json", JSON.writeValueAsBytes(manifest)); files.remove("CHECKSUMS.sha256");
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    @Test void aPackageForThisReleaseIsCompatible() throws Exception {
        assertEquals(List.of(), PackageCompatibility.problems(manifest()));
        assertEquals(List.of(), PackageCompatibility.hostProblems(manifest(), "host-1", host("0.10.0", 2, 2)));
    }

    @Test void eachIncompatibilityIsReported() throws Exception {
        Map<String, Consumer<ObjectNode>> cases = new LinkedHashMap<>();
        cases.put("contracts major", m -> ((ObjectNode) m.get("compatibility")).put("contractsVersion", "2.0.0"));
        cases.put("contracts missing", m -> ((ObjectNode) m.get("compatibility")).remove("contractsVersion"));
        cases.put("minimum host not a version", m -> ((ObjectNode) m.get("compatibility")).put("minimumToolHostVersion", "latest"));
        cases.put("descriptor generation", m -> ((ObjectNode) m.get("sdk")).put("descriptorGeneration", 2));
        cases.put("protocol", m -> ((ObjectNode) m.get("sdk")).putObject("protocol").put("min", 3).put("max", 4));
        cases.put("protocol not a range", m -> ((ObjectNode) m.get("sdk")).putObject("protocol").put("min", 3).put("max", 2));
        cases.put("runtime", m -> ((ObjectNode) m.get("runtimes").get(0)).put("language", "dotnet"));
        cases.put("no runtime", m -> m.putArray("runtimes"));
        for (var c : cases.entrySet()) {
            var problems = PackageCompatibility.problems(edited(c.getValue()));
            assertEquals(1, problems.size(), c.getKey() + ": " + problems);
        }
        assertEquals(1, PackageCompatibility.runtimeProblems(edited(cases.get("runtime"))).size());
    }

    @Test void aToolHostMustBeNewEnoughAndShareAProtocol() throws Exception {
        var needs = edited(m -> ((ObjectNode) m.get("compatibility")).put("minimumToolHostVersion", "0.11.0"));
        assertEquals(1, PackageCompatibility.hostProblems(needs, "host-1", host("0.10.0", 2, 2)).size());
        assertEquals(1, PackageCompatibility.hostProblems(needs, "host-1", host("0.11.0-rc.1", 2, 2)).size(), "a pre-release precedes its release");
        assertEquals(List.of(), PackageCompatibility.hostProblems(needs, "host-1", host("0.11.0", 2, 2)));
        assertEquals(List.of(), PackageCompatibility.hostProblems(needs, "host-1", host("1.0.0", 1, 3)));
        assertEquals(1, PackageCompatibility.hostProblems(manifest(), "host-1", host("0.10.0", 3, 3)).size());
        assertEquals(1, PackageCompatibility.hostProblems(manifest(), "host-1", JSON.readTree("{\"version\":\"0.10.0\"}")).size(), "a host that reports no protocol");
    }

    @Test void uploadRefusesWhatNoToolHostCanRunAndDeployRefusesATooOldHost() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(SqliteState.open(directory.resolve("compatibility.sqlite")), codec);
        var tenant = new Ids.TenantId("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());

        // A runtime or descriptor generation mismatch already fails the package rules (400); these pass them and fail here.
        var contracts = ServerPackagesTest.zip(files(m -> ((ObjectNode) m.get("compatibility")).put("contractsVersion", "2.0.0")));
        assertEquals(422, assertThrows(Failure.class, () -> packages.upload(actor, contracts, "request-1")).status());
        var protocol = ServerPackagesTest.zip(files(m -> ((ObjectNode) m.get("sdk")).putObject("protocol").put("min", 3).put("max", 4)));
        assertEquals(422, assertThrows(Failure.class, () -> packages.upload(actor, protocol, "request-2")).status());
        assertEquals(0, store.transaction(tenant, false, tx -> tx.packages().versions(10)).size(), "nothing is stored");

        var needsNewHost = ServerPackagesTest.zip(files(m -> ((ObjectNode) m.get("compatibility")).put("minimumToolHostVersion", "0.11.0")));
        String digest = JSON.readTree(packages.upload(actor, needsNewHost, "request-3").body()).get("packageDigest").asText();
        var device = new EndpointDeviceRecord("host-1", "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null);
        packages.register(tenant, tx -> device, "{\"hostId\":\"host-1\",\"poolId\":\"pool\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "request-4");
        String body = "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"},\"hostIds\":[\"host-1\"]}";
        assertEquals(422, assertThrows(Failure.class, () -> packages.deploy(actor, body, "request-5")).status());
        assertTrue(store.transaction(tenant, false, tx -> tx.packages().deployments()).isEmpty());
        // Once the host reports a new enough version, the same deployment goes through.
        packages.register(tenant, tx -> device, "{\"hostId\":\"host-1\",\"poolId\":\"pool\",\"version\":\"0.11.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.11.0\",\"tiers\":[\"HOST_NATIVE\"]}", "request-6");
        assertEquals(1, JSON.readTree(packages.deploy(actor, body, "request-7").body()).get("revision").asLong());
    }
}
