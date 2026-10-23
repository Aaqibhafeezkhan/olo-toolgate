// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.ControlAuditPage;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M4.4: the audit store keeps evidence beside the row and returns it in the contract's page shape. */
class AuditEvidenceStoreTest {
    @TempDir Path temp;
    private static final Ids.TenantId TENANT = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant");

    @Test void evidenceRoundTripsAndRowsWithoutItStayValid() throws Exception {
        var codec = new ContractCodec();
        try (var source = SqliteState.openPooled(temp.resolve("audit.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec);
            String evidence = "{\"authorizationEpoch\":3,\"decision\":\"ALLOW\",\"invocationId\":\"inv-1\",\"mode\":\"HUMAN\",\"operation\":\"read\","
                    + "\"policyRevision\":7,\"resourceCount\":0,\"resourcesDigest\":\"" + "e".repeat(64) + "\",\"state\":\"QUEUED\",\"subjectUserId\":\"user-42\",\"toolId\":\"olo.memory\"}";
            store.transaction(TENANT, true, tx -> {
                tx.audit("a".repeat(64), "INVOCATION_SUBMIT", "invocation:inv-1", 1, "inv-1", "b".repeat(64), evidence);
                tx.audit("a".repeat(64), "CREATE", "users:u1", 2, "req-2", "c".repeat(64));
                return null;
            });
            String page = store.transaction(TENANT, false, tx -> tx.auditPage(0, 20));
            var parsed = codec.model(page, ControlAuditPage.class);
            assertEquals(2, parsed.items().size());
            assertEquals("user-42", parsed.items().get(0).evidence().subjectUserId());
            assertEquals("ALLOW", parsed.items().get(0).evidence().decision().name());
            assertNull(parsed.items().get(1).evidence());
            assertThrows(RuntimeException.class, () -> store.transaction(TENANT, true, tx -> {
                tx.audit("a".repeat(64), "INVOCATION_SUBMIT", "invocation:inv-2", 3, "inv-2", "d".repeat(64), "x".repeat(5000));
                return null;
            }), "oversized evidence is refused by the schema");
        }
    }
}
