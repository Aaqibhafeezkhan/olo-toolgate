// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DeviceFileQualificationTest {
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
    @Test void closedReadWriteCandidateStillNeedsExplicitRuntimeQualification()throws Exception{
        var value=descriptor();value.withArray("permissions").add("DEVICE_FILES_WRITE");((ObjectNode)value.path("effect")).put("safety","IDEMPOTENT");
        var proof=DeviceFileQualification.evidence(value,JSON.createObjectNode().put("fileAccess","WRITE"),runtime());
        assertNotNull(proof);assertTrue(proof.path("writeAccess").asBoolean());
        value.withArray("permissions").add("NETWORK");assertNull(DeviceFileQualification.evidence(value,JSON.createObjectNode().put("fileAccess","WRITE"),runtime()));
    }
    @Test void onlyClosedReadOnlyFileCapabilitiesProduceQualificationEvidence() throws Exception {
        var registration = JSON.createObjectNode().put("fileAccess", "READ");
        assertNotNull(DeviceFileQualification.evidence(descriptor(), registration, runtime()));
        for (String field : new String[]{"routes", "destinations", "httpAuth", "tcp"}) {
            var bad = descriptor(); bad.withArray(field).addObject().put("id", "network");
            assertNull(DeviceFileQualification.evidence(bad, registration, runtime()));
        }
        for (String field : new String[]{"secrets", "globals", "claims"}) {
            var bad = descriptor(); ((ObjectNode) bad.path("variables")).withArray(field).addObject().put("name", "private");
            assertNull(DeviceFileQualification.evidence(bad, registration, runtime()));
        }
        for (String safety : new String[]{"UNKNOWN", "NON_IDEMPOTENT", "IDEMPOTENT"}) {
            var bad = descriptor(); ((ObjectNode) bad.path("effect")).put("safety", safety);
            assertNull(DeviceFileQualification.evidence(bad, registration, runtime()));
        }
        var write = descriptor(); write.withArray("permissions").add("DEVICE_FILES_WRITE");
        assertNull(DeviceFileQualification.evidence(write, registration, runtime()));
        var broad = descriptor(); ((ObjectNode) broad.path("effect")).put("openWorld", true);
        assertNull(DeviceFileQualification.evidence(broad, registration, runtime()));
        assertNull(DeviceFileQualification.evidence(descriptor(), registration.deepCopy().put("fileAccess", "WRITE"), runtime()));
        assertNull(DeviceFileQualification.evidence(descriptor(), registration, runtime().put("image", "registry/tool:mutable")));
        assertNull(DeviceFileQualification.evidence(descriptor(), registration, runtime().put("kind", "PYTHON_SDK")));
    }
}
