// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The reservation rules of invocation-state-v2.md §4.3 against the SQLite store. */
class ReservationsTest {
    final AtomicLong now = new AtomicLong(1_000_000);

    Reservations open(Path file) throws Exception {
        return Reservations.open(file, 10_000, now::get);
    }

    static Path file() throws Exception {
        return Files.createTempDirectory("toolgate-reservations").resolve("state.db");
    }

    @Test
    void outcomesFollowTheOrderOfTheRules() throws Exception {
        try (Reservations r = open(file())) {
            assertEquals(Reservations.Kind.NEW, r.reserve("t", "TENANT", "", "k", "f1", "alice", "inv-1").kind());
            assertEquals(Reservations.Kind.EXISTING, r.reserve("t", "TENANT", "", "k", "f1", "alice", "inv-2").kind());
            assertEquals(Reservations.Kind.CONFLICT, r.reserve("t", "TENANT", "", "k", "f2", "alice", "inv-3").kind());
            // Another caller learns nothing, whether or not the fingerprint matches.
            Reservations.Outcome same = r.reserve("t", "TENANT", "", "k", "f1", "bob", "inv-4");
            Reservations.Outcome other = r.reserve("t", "TENANT", "", "k", "f2", "bob", "inv-5");
            assertEquals(Reservations.Kind.IN_USE, same.kind());
            assertEquals(same, other);
            assertNull(same.invocationId());
            // Namespaces and tools are separate key spaces.
            assertEquals(Reservations.Kind.NEW, r.reserve("t", "AGENT", "bob", "k", "f1", "bob", "inv-6").kind());
            assertEquals(Reservations.Kind.NEW, r.reserve("u", "TENANT", "", "k", "f1", "bob", "inv-7").kind());
        }
    }

    @Test
    void anExistingRowCarriesItsResultAndOnlyNotStartedWorkIsRebound() throws Exception {
        try (Reservations r = open(file())) {
            r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-1");
            ObjectNode result = Fixtures.JSON.createObjectNode().put("isError", false);
            r.finish("inv-1", "COMPLETED", result);
            Reservations.Outcome existing = r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-2");
            assertEquals("inv-1", existing.invocationId());
            assertEquals(result, existing.result());

            r.reserve("t", "AGENT", "a", "n", "f", "a", "inv-3");
            r.finish("inv-3", Reservations.NOT_STARTED, null);
            Reservations.Outcome rebound = r.reserve("t", "AGENT", "a", "n", "f", "a", "inv-4");
            assertEquals(Reservations.Kind.NEW, rebound.kind());
            assertEquals("inv-4", rebound.invocationId());

            r.reserve("t", "AGENT", "a", "x", "f", "a", "inv-5");
            r.finish("inv-5", "FAILED", result);
            assertEquals(Reservations.Kind.EXISTING, r.reserve("t", "AGENT", "a", "x", "f", "a", "inv-6").kind(), "a failure is never re-run");
        }
    }

    @Test
    void retentionEndsInATombstone() throws Exception {
        try (Reservations r = open(file())) {
            r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-1");
            r.finish("inv-1", "COMPLETED", Fixtures.JSON.createObjectNode());
            now.addAndGet(10_000);
            assertEquals(Reservations.Kind.EXPIRED, r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-2").kind());
            assertEquals(Reservations.Kind.EXPIRED, r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-3").kind(), "and stays expired");
        }
    }

    @Test
    void workRunningWhenTheProcessStoppedIsUnknownAfterwards() throws Exception {
        Path file = file();
        String hash;
        try (Reservations r = open(file)) {
            r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-1");
            hash = r.identityHash("a");
        }
        try (Reservations r = open(file)) {
            Reservations.Outcome o = r.reserve("t", "AGENT", "a", "k", "f", "a", "inv-2");
            assertEquals(Reservations.Kind.EXISTING, o.kind());
            assertEquals("OUTCOME_UNKNOWN", o.state());
            assertEquals(hash, r.identityHash("a"), "the identity hash key persists");
            assertNotEquals(hash, r.identityHash("b"));
        }
        try (Reservations r = open(file())) {
            assertNotEquals(hash, r.identityHash("a"), "and is per store");
        }
    }
}
