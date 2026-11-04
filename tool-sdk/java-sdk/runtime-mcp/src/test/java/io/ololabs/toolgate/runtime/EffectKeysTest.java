// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectKeysTest {
    @Test void keysReachTheBrokerAsMetadataAndNeverBecomeCallerHeaders()throws Exception {
        var json=new ObjectMapper();
        var config=Config.load(Map.of("TOOLGATE_MODE","test"),Path.of("."));
        var egress=Egress.resolve(config,List.of(),System.err);
        var seen=new ArrayList<JsonNode>();
        egress.exchanges=request->{seen.add(request.deepCopy());return json.createObjectNode().put("status",200);};
        var route=json.readTree("{\"id\":\"write\",\"destination\":\"api\",\"method\":\"POST\",\"path\":\"/send\",\"headers\":[],\"query\":[],\"requestContentTypes\":[\"application/json\"]}");
        var client=new RouteClient(egress,Map.of("write",route),Map.of(),Map.of(),null,Instant.now().plusSeconds(60),new Redactor());
        client.route("write").effectKey("charge:7").body(Map.of("amount",7)).sendRaw();
        assertEquals("charge:7",seen.get(0).path("effectKey").asText());
        assertEquals(7,seen.get(0).path("body").path("amount").asInt());
        assertTrue(seen.get(0).path("headers").isEmpty());
        for(String key:Arrays.asList(null,"","a/b","é","x".repeat(129)))
            assertThrows(PlatformError.class,()->client.route("write").effectKey(key));
        assertEquals(1,seen.size());
        egress.exchanges=null;
        assertThrows(PlatformError.class,()->client.route("write").effectKey("charge:7").sendRaw());
        assertEquals(1,seen.size());
    }
}
