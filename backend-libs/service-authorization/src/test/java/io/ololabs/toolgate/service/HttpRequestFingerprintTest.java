// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpRequestFingerprintTest {
    @Test void actualWireFactsMatchBrokerVectors()throws Exception{
        var data=new ObjectMapper().readTree(Files.readString(Path.of(System.getProperty("toolgate.httpBindingVectors"))));
        for(var vector:data.path("vectors")){
            var m=vector.path("manifest");var headers=new LinkedHashMap<String,List<String>>();
            m.path("headers").properties().forEach(e->{var values=new ArrayList<String>();e.getValue().forEach(v->values.add(v.asText()));headers.put(e.getKey(),values);});
            headers.put("content-type",List.of(m.path("contentType").asText()));
            headers.put("authorization",List.of("secret-not-bound"));headers.put("host",List.of("example.test"));
            var profile=new HttpRequestFingerprint.Profile(m.path("profileId").asText(),m.path("profileDigest").asText(),
                m.path("routeId").asText(),m.path("authDeclarationId").asText(),List.of("authorization"));
            assertEquals(vector.path("digest").asText(),HttpRequestFingerprint.digest(profile,
                URI.create(vector.path("url").asText()),m.path("method").asText(),headers,
                vector.path("body").asText().getBytes(StandardCharsets.UTF_8)),vector.path("name").asText());
        }
    }
    @Test void fingerprintsPreserveUnicodeEscapesIpv6AndArbitraryBodyBytes(){
        var p=new HttpRequestFingerprint.Profile("p\u0301","sha256:"+"a".repeat(64),"write",null,List.of());
        var digest=HttpRequestFingerprint.digest(p,URI.create("https://[::1]:8443/a%2Fb?a=2&a=1"),"POST",
            Map.of("x-value",List.of("a\"\\\t")),new byte[]{0,(byte)255});
        assertEquals("sha256:1ed00dc960a3cbfa03fe1f6c90d32144f85392a47c1f8b3240c0b3af3902384b",digest);
    }
    @Test void actualQueryContentTypeAndRecipientChangesChangeTheDigest(){
        var p=new HttpRequestFingerprint.Profile("p","sha256:"+"a".repeat(64),"r",null,List.of());
        var uri=URI.create("https://example.test/write?a=2&a=1");
        var headers=Map.of("content-type",List.of("application/json"),"x-recipient",List.of("alice"));
        var body="payload".getBytes(StandardCharsets.UTF_8);
        String original=HttpRequestFingerprint.digest(p,uri,"POST",headers,body);
        assertNotEquals(original,HttpRequestFingerprint.digest(p,URI.create("https://example.test/write?a=1&a=2"),"POST",headers,body));
        assertNotEquals(original,HttpRequestFingerprint.digest(p,uri,"POST",Map.of("content-type",List.of("text/plain"),"x-recipient",List.of("alice")),body));
        assertNotEquals(original,HttpRequestFingerprint.digest(p,uri,"POST",Map.of("content-type",List.of("application/json"),"x-recipient",List.of("bob")),body));
        assertThrows(IllegalArgumentException.class,()->HttpRequestFingerprint.digest(p,uri,"POST",Map.of("authorization",List.of("secret")),body));
        assertThrows(IllegalArgumentException.class,()->HttpRequestFingerprint.digest(p,uri,"POST",Map.of("content-type",List.of("a","b")),body));
    }
}
