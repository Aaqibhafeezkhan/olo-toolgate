// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.control.application.Canonical;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Control and the broker must agree on the byte-level binding before the generalized grant API can consume it. */
class HttpRequestBindingVectorTest {
    @Test void sharedPreparedTransportVectorsUseJcsWithoutNfc()throws Exception{
        var fixtures=Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().getParent().getParent();
        var root=new ObjectMapper().readTree(Files.readString(fixtures.resolve("fixtures/security/http-request-binding-v1.json")));
        for(var vector:root.path("vectors"))assertEquals(vector.path("digest").asText(),Canonical.arguments(vector.path("manifest")),vector.path("name").asText());
    }
}
