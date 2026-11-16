// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DeviceFileReadQualificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ObjectNode descriptor() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"toolId":"files/read","kind":"CLIENT","permissions":["DEVICE_FILES_READ"],
             "effect":{"safety":"READ_ONLY","openWorld":false,"destructive":false},
             "execution":{"async":false},"routes":[],"destinations":[],"httpAuth":[],"tcp":[],
             "variables":{"secrets":[],"globals":[],"claims":[]}}
            """);
    }
    private ObjectNode runtime() {
        return JSON.createObjectNode().put("kind", "JAVA_SDK").put("image", "registry/tool@sha256:" + "a".repeat(64));
    }
    @Test void onlyClosedReadOnlyFileCapabilitiesProduceQualificationEvidence() throws Exception {
        var registration = JSON.createObjectNode().put("fileAccess", "READ");
        assertNotNull(DeviceFileReadQualification.evidence(descriptor(), registration, runtime()));
        for (String field : new String[]{"routes", "destinations", "httpAuth", "tcp"}) {
            var bad = descriptor(); bad.withArray(field).addObject().put("id", "network");
            assertNull(DeviceFileReadQualification.evidence(bad, registration, runtime()));
        }
        for (String field : new String[]{"secrets", "globals", "claims"}) {
            var bad = descriptor(); ((ObjectNode) bad.path("variables")).withArray(field).addObject().put("name", "private");
            assertNull(DeviceFileReadQualification.evidence(bad, registration, runtime()));
        }
        for (String safety : new String[]{"UNKNOWN", "NON_IDEMPOTENT", "IDEMPOTENT"}) {
            var bad = descriptor(); ((ObjectNode) bad.path("effect")).put("safety", safety);
            assertNull(DeviceFileReadQualification.evidence(bad, registration, runtime()));
        }
        var write = descriptor(); write.withArray("permissions").add("DEVICE_FILES_WRITE");
        assertNull(DeviceFileReadQualification.evidence(write, registration, runtime()));
        var broad = descriptor(); ((ObjectNode) broad.path("effect")).put("openWorld", true);
        assertNull(DeviceFileReadQualification.evidence(broad, registration, runtime()));
        assertNull(DeviceFileReadQualification.evidence(descriptor(), registration.deepCopy().put("fileAccess", "WRITE"), runtime()));
        assertNull(DeviceFileReadQualification.evidence(descriptor(), registration, runtime().put("image", "registry/tool:mutable")));
        assertNull(DeviceFileReadQualification.evidence(descriptor(), registration, runtime().put("kind", "PYTHON_SDK")));
    }
}
