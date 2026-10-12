// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the shared wire corpus through actual Jackson record bindings. */
final class ContractRoundTripTest {
    private final ObjectMapper mapper = JsonMapper.builder()
        .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    @Test void everyGeneratedModelRoundTrips() throws Exception {
        var fixtures = mapper.readTree(Path.of(System.getProperty("toolgate.fixtures")).toFile());
        var fields = fixtures.properties().iterator();
        int checked = 0;
        while (fields.hasNext()) {
            var fixture = fields.next();
            Class<?> type;
            try {
                type = Class.forName("io.ololabs.toolgate.contracts." + fixture.getKey());
            } catch (ClassNotFoundException primitiveDefinition) {
                // Primitive definitions (identifiers, digests, names) have no generated class.
                continue;
            }
            var model = mapper.treeToValue(fixture.getValue(), type);
            assertEquals(fixture.getValue(), mapper.readTree(mapper.writeValueAsString(model)), fixture.getKey());
            checked++;
        }
        assertEquals(254, checked);
        assertEquals(ContractSet.NAME, ContractSet.current().name());
        assertEquals(System.getProperty("toolgate.contractsVersion"), ContractSet.current().version());
    }

    @Test void invalidSecuritySemanticsNeverBecomeAllow() {
        assertThrows(Exception.class, () -> mapper.readValue("\"UNKNOWN\"", Decision.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"reason\":\"NO_GRANT\",\"policyVersion\":\"0.1.0-dev\",\"requestId\":\"r\"}", EnterpriseDecision.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"decision\":\"BLOCK\",\"reason\":\"NO_GRANT\",\"policyVersion\":\"0.1.0-dev\",\"requestId\":\"r\",\"bypass\":true}", EnterpriseDecision.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"code\":\"INTERNAL\",\"requestId\":\"r\",\"retryable\":null}", ErrorEnvelope.class));
    }
}
