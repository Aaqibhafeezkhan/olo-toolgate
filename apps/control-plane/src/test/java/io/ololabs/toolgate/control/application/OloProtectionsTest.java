// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OloProtectionsTest {
    private final OloProtections olo = new OloProtections(Set.of("agent-olo-worker"), Set.of("olo-memory"));
    private static final EnterpriseConditions OPEN = new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),true,false,null,null);

    @Test void reservedPrincipalIsServiceOnly() {
        assertDoesNotThrow(() -> olo.workload(binding("agent-olo-worker", RequestMode.SERVICE)));
        assertThrows(IllegalArgumentException.class, () -> olo.workload(binding("agent-olo-worker", RequestMode.DELEGATED)));
        assertDoesNotThrow(() -> olo.workload(binding("agent-planner", RequestMode.DELEGATED)));
        assertTrue(olo.reserved("agent-olo-worker"));
        assertFalse(olo.reserved(null));
        assertFalse(OloProtections.NONE.reserved("agent-olo-worker"));
    }

    @Test void protectedToolGroupsRefuseActionAndResourceWildcards() {
        var exact = List.of(new EnterpriseResourceRule(ResourceKind.CUSTOM, "olo://memory/session", EnterpriseResourceMatch.PREFIX));
        var any = List.of(new EnterpriseResourceRule(ResourceKind.CUSTOM, "", EnterpriseResourceMatch.ANY));
        assertDoesNotThrow(() -> olo.scope(scope(List.of("olo-memory"), false, List.of("read"), false, exact)));
        assertThrows(IllegalArgumentException.class, () -> olo.scope(scope(List.of("olo-memory"), false, List.of(), true, exact)));
        assertThrows(IllegalArgumentException.class, () -> olo.scope(scope(List.of("olo-memory"), false, List.of("read"), false, any)));
        // The tool-group wildcard reaches protected groups too.
        assertThrows(IllegalArgumentException.class, () -> olo.scope(scope(List.of(), true, List.of(), true, exact)));
        // Unprotected groups and unconfigured installations keep ordinary wildcards.
        assertDoesNotThrow(() -> olo.scope(scope(List.of("demo-tools"), false, List.of(), true, any)));
        assertDoesNotThrow(() -> OloProtections.NONE.scope(scope(List.of(), true, List.of(), true, any)));
    }

    private static EnterpriseScope scope(List<String> groups, boolean allGroups, List<String> actions, boolean allActions, List<EnterpriseResourceRule> resources) {
        return new EnterpriseScope(new GroupSelection(groups, allGroups), new GroupSelection(List.of(), true), actions, allActions, resources, OPEN);
    }

    private static ControlWorkloadBinding binding(String agent, RequestMode mode) {
        boolean delegated = mode == RequestMode.DELEGATED;
        return new ControlWorkloadBinding("workload-1", "Workload", true, 1L, agent, mode, "https://issuer", "subject", "toolgate",
                "a".repeat(64), 1L, 0L, delegated ? "user-1" : null, null, delegated ? 1L : null, java.util.Map.of(), false);
    }
}
