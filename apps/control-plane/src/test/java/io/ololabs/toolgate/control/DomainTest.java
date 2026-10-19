// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.control.application.DirectoryService;
import io.ololabs.toolgate.control.application.Failure;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Domain graph, typed identity and shared wire validation negatives. */
final class DomainTest {
    private final ContractCodec codec = new ContractCodec();
    static String user(String id, long revision) {
        return "{\"id\":\"" + id + "\",\"name\":\"User\",\"enabled\":true,\"revision\":" + revision + "}";
    }
    @Test void identitiesAndGraphFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Ids.TenantId("../"));
        assertNotEquals(new Ids.UserId("same"), new Ids.TeamId("same"));
        var team = codec.entry(Kind.TEAM, "{\"id\":\"team\",\"name\":\"Team\",\"enabled\":true,\"revision\":1,\"userIds\":[\"user\"],\"roleIds\":[]}");
        var member = codec.entry(Kind.USER, user("user", 1));
        assertThrows(IllegalArgumentException.class, () -> new Directory(0, Map.of(team.id(), team)).validate(512, 1048576));
        var entries=new java.util.HashMap<Ids.RecordId,Directory.Entry>();io.ololabs.toolgate.control.application.GroupGraph.defaults(entries,codec);entries.put(team.id(),team);entries.put(member.id(),member);var directory = new Directory(0, entries);
        directory.validate(512, 1048576);
        assertThrows(IllegalArgumentException.class, () -> directory.validate(1, 1048576));
        assertThrows(IllegalArgumentException.class, () -> directory.validate(512, 1));
        assertThrows(Failure.class, () -> new DirectoryService.Actor(io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"), "a".repeat(64), false).requireAdmin());
    }
    @Test void unknownMissingDuplicateAndMalformedFieldsReject() {
        for (var value : java.util.List.of("{}", "null", "{", user("user", 0), user("user", 1).replace("\"enabled\":true", "\"enabled\":\"true\""),
                user("user", 1).replace("\"name\":\"User\"", "\"name\":\"User\",\"name\":\"Other\""),
                user("user", 1).replace("\"revision\":1", "\"revision\":1,\"password\":\"forbidden\""), user("user", 1) + "{}")) {
            assertThrows(Failure.class, () -> codec.entry(Kind.USER, value));
        }
    }
    @Test void yamlCustomTagsAndAliasesReject() {
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant");
        assertThrows(Failure.class, () -> codec.input("!!java/object:java.lang.ProcessBuilder {}", true, tenant));
        assertThrows(Failure.class, () -> codec.input("a: &x [1]\nb: *x", true, tenant));
        assertThrows(Failure.class, () -> codec.input("snapshot: {}\nsnapshot: {}", true, tenant));
    }
    @Test void canonicalSnapshotsRoundTripBothFormats() {
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var member = codec.entry(Kind.USER, user("user", 1));
        var directory = new Directory(0, Map.of(member.id(), member));
        var request = "{\"snapshot\":" + codec.snapshot(tenant, directory, false) + ",\"mode\":\"MERGE\",\"dryRun\":true}";
        assertEquals(directory, codec.input(request, false, tenant).directory());
        var yaml = "mode: MERGE\ndryRun: true\nsnapshot:\n" + codec.snapshot(tenant, directory, true).replaceFirst("---\\s*", "").lines().map(s -> "  " + s + "\n").collect(java.util.stream.Collectors.joining());
        assertEquals(directory, codec.input(yaml, true, tenant).directory());
        assertThrows(Failure.class, () -> codec.input(request, false, io.ololabs.toolgate.control.fixtures.Tenants.id("other")));
    }
    @Test void servedOpenApiContainsCanonicalModelsAndNoExternalReferences() {
        var api = codec.openapi();
        assertTrue(api.contains("ControlSnapshot"));
        assertTrue(api.contains("#/components/schemas/ControlUser"));
        assertFalse(api.contains("../schemas/"));
    }
}
