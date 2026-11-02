// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RecoveryAdmissionPolicyTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String IMAGE="sha256:"+"a".repeat(64);
    private ObjectNode runtime(){return JSON.createObjectNode().put("runtimeId","python").put("language","python")
            .put("version","3.12").put("baseImageDigest",IMAGE);}
    private ObjectNode descriptor(){
        var value=JSON.createObjectNode().put("kind","SERVER");
        value.putArray("permissions").add("HTTP_ROUTE");value.putArray("routes").addObject().put("id","append");
        value.putArray("httpAuth");value.putArray("tcp");value.putObject("variables").putArray("secrets");
        return value;
    }
    private ObjectNode profile(){
        var value=JSON.createObjectNode().put("profileId","olo").put("revision",1);
        value.putArray("routes").addObject().put("id","append").put("destination","olo-memory")
                .put("method","POST").put("path","/v1/memory.append").put("streaming","NONE");
        value.putArray("destinations").addObject().put("id","olo-memory").put("scheme","https").put("mode","BROKERED");
        return value;
    }
    private ObjectNode registration(){
        var value=JSON.createObjectNode().put("hostId","host").put("poolId","qualified-pool");
        value.putArray("tiers").add("OCI_SANDBOX");return value;
    }
    private RecoveryAdmissionPolicy.Scope qualify(ObjectNode runtime,ObjectNode descriptor,ObjectNode profile){
        return RecoveryAdmissionPolicy.qualify(runtime,descriptor,profile,registration(),Set.of("host@qualified-pool@"+IMAGE));
    }
    @Test void configurationIsBoundedAndMalformedPinsCannotSilentlyEnableRecovery(){
        assertEquals(Set.of(),RecoveryAdmissionPolicy.parseQualifiedExecutors(""));
        assertEquals(Set.of("host@pool@"+IMAGE),RecoveryAdmissionPolicy.parseQualifiedExecutors("host@pool@"+IMAGE));
        for(var pin:Set.of("pool@"+IMAGE,"host@pool@latest","host@pool@"+IMAGE+",","*@pool@"+IMAGE))
            assertThrows(IllegalArgumentException.class,()->RecoveryAdmissionPolicy.parseQualifiedExecutors(pin),pin);
    }
    @Test void independentlyPinnedScopeBindsEveryQualificationDocument(){
        var runtime=runtime();var descriptor=descriptor();var profile=profile();
        var scope=qualify(runtime,descriptor,profile);assertNotNull(scope);
        assertEquals(Canonical.digest(runtime),scope.runtimeDigest());
        assertEquals(Canonical.digest(descriptor),scope.descriptorDigest());
        assertEquals(Canonical.digest(profile),scope.profileDigest());
        assertEquals(IMAGE,scope.imageDigest());
    }
    @Test void selfReportedTierOrQualificationCannotReplaceOperatorImagePoolPin(){
        var registration=registration();registration.put("qualified",true);
        assertNull(RecoveryAdmissionPolicy.qualify(runtime(),descriptor(),profile(),registration,Set.of()));
        assertNull(RecoveryAdmissionPolicy.qualify(runtime(),descriptor(),profile(),registration,Set.of("host@other-pool@"+IMAGE)));
        registration.put("hostId","different-device");
        assertNull(RecoveryAdmissionPolicy.qualify(runtime(),descriptor(),profile(),registration,Set.of("host@qualified-pool@"+IMAGE)));
        var runtime=runtime();runtime.put("baseImageDigest","sha256:"+"b".repeat(64));
        assertNull(qualify(runtime,descriptor(),profile()));
        runtime.remove("baseImageDigest");assertNull(qualify(runtime,descriptor(),profile()));
        runtime=runtime().put("runtimeId","jvm");assertNull(qualify(runtime,descriptor(),profile()));
    }
    @Test void credentialsTunnelsFilesAndOtherUnmediatedPermissionsRefuseQualification(){
        for(var permission:Set.of("CREDENTIALS_IN_SANDBOX","USER_SECRET","TCP","CONNECTION","TOKEN_EXCHANGE","DEVICE_FILES_READ","DEVICE_FILES_WRITE")){
            var descriptor=descriptor();descriptor.withArray("permissions").add(permission);
            assertNull(qualify(runtime(),descriptor,profile()),permission);
        }
    }
    @Test void missingOrHiddenCapabilityDeclarationsCannotQualifyWithMislabelledPermissions(){
        for(var field:Set.of("httpAuth","tcp")){
            var value=descriptor();value.remove(field);
            assertNull(qualify(runtime(),value,profile()),field);
            value=descriptor();value.withArray(field).addObject().put("id","undeclared-capability");
            assertNull(qualify(runtime(),value,profile()),field);
        }
        var value=descriptor();((ObjectNode)value.path("variables")).remove("secrets");
        assertNull(qualify(runtime(),value,profile()));
        value=descriptor();((ObjectNode)value.path("variables")).withArray("secrets").addObject().put("name","ApiKey");
        assertNull(qualify(runtime(),value,profile()));
    }
    @Test void ordinaryHttpStreamingAndPackageCredentialsAreNotQualified(){
        for(var field:Set.of("destination","method","path","streaming","auth")){
            var profile=profile();var route=(ObjectNode)profile.withArray("routes").get(0);
            route.put(field,switch(field){case "destination"->"ordinary-api";case "method"->"GET";case "path"->"/v1/unknown";case "streaming"->"BIDIRECTIONAL";default->"api-key";});
            assertNull(qualify(runtime(),descriptor(),profile),field);
        }
        for(var field:Set.of("scheme","mode")){
            var profile=profile();((ObjectNode)profile.withArray("destinations").get(0)).put(field,field.equals("scheme")?"http":"DIRECT");
            assertNull(qualify(runtime(),descriptor(),profile),field);
        }
    }
    @Test void missingDuplicateOrAdditionalRoutesCannotHideUnmediatedPaths(){
        var descriptor=descriptor();descriptor.withArray("routes").addObject().put("id","missing");
        assertNull(qualify(runtime(),descriptor,profile()));
        descriptor=descriptor();descriptor.withArray("routes").addObject().put("id","append");
        assertNull(qualify(runtime(),descriptor,profile()));
        var profile=profile();profile.withArray("routes").add(profile.withArray("routes").get(0).deepCopy());
        assertNull(qualify(runtime(),descriptor(),profile));
    }
}
