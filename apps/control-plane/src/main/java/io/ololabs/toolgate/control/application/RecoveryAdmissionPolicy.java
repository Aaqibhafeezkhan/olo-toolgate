// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Independent admission-time qualification; an executor's later runtime report is never the source. */
public final class RecoveryAdmissionPolicy {
    public record Scope(String deviceId,String poolId,String imageDigest,String runtimeDigest,String descriptorDigest,
                 String profileDigest,long profileRevision) {}
    private RecoveryAdmissionPolicy() {}

    /** Operator configuration only: authenticated device, administered pool and exact OCI image. */
    public static Set<String> parseQualifiedExecutors(String configured) {
        if(configured.isBlank())return Set.of();
        var entries=configured.split(",",-1);
        if(entries.length>1024)throw new IllegalArgumentException("Too many recovery executor pins");
        var pins=new HashSet<String>();
        for(var entry:entries){
            String value=entry.strip();
            if(!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}@[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}@sha256:[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid recovery executor pin");
            pins.add(value);
        }
        return Set.copyOf(pins);
    }

    static Scope qualify(JsonNode runtime,JsonNode descriptor,JsonNode profile,JsonNode registration,
                         Set<String> qualifiedPoolImages) {
        String device=registration.path("hostId").asText("");
        String pool=registration.path("poolId").asText("");
        String image=runtime.path("baseImageDigest").asText("");
        if(!runtime.path("runtimeId").asText().equals("python")
                ||!runtime.path("language").asText().equals("python")
                ||!image.matches("sha256:[a-f0-9]{64}")
                ||device.isEmpty()||pool.isEmpty()||!qualifiedPoolImages.contains(device+"@"+pool+"@"+image)
                ||!descriptor.path("kind").asText().equals("SERVER")
                ||!arrayContains(registration.path("tiers"),"OCI_SANDBOX")
                ||!descriptor.path("permissions").isArray()
                ||!descriptor.path("httpAuth").isArray()
                ||!descriptor.path("tcp").isArray()||!descriptor.path("tcp").isEmpty()
                ||!descriptor.path("variables").path("secrets").isArray()
                ||!descriptor.path("variables").path("secrets").isEmpty()
                ||!descriptor.path("routes").isArray()
                ||!profile.path("routes").isArray()||!profile.path("destinations").isArray()
                ||!profile.path("revision").isIntegralNumber()||!profile.path("revision").canConvertToLong()||profile.path("revision").asLong()<1)return null;
        for(var permission:descriptor.path("permissions"))
            if(!permission.isTextual()||!permission.asText().equals("HTTP_ROUTE"))return null;
        var authIds=new HashSet<String>();
        for(var auth:descriptor.path("httpAuth")){
            String id=auth.path("id").asText("");
            if(id.isEmpty()||!authIds.add(id)||!brokerAuth(descriptor,id))return null;
        }
        var ids=new HashSet<String>();
        for(var declared:descriptor.path("routes")){
            String id=declared.path("id").asText("");
            if(id.isEmpty()||!ids.add(id))return null;
            JsonNode route=null;
            for(var candidate:profile.path("routes"))if(candidate.path("id").asText().equals(id)){
                if(route!=null)return null;route=candidate;
            }
            if(route==null||!route.path("streaming").asText().equals("NONE"))return null;
            if(route.hasNonNull("auth")&&!brokerAuth(descriptor,route.path("auth").asText()))return null;
            String destination=route.path("destination").asText();
            String path=route.path("path").asText();
            boolean supported=destination.equals("olo-memory")
                    ? Set.of("/v1/memory.read","/v1/memory.append","/v1/memory.delete").contains(path)
                    : destination.equals("olo-rag")
                    &&Set.of("/v1/rag.query","/v1/rag.ingest","/v1/rag.update","/v1/rag.delete").contains(path);
            // Generic HTTP is qualified only when every declared route is explicitly
            // reviewed and remains entirely behind the broker. Raw worker secrets,
            // non-API-key auth, sockets and streaming still refuse qualification.
            if(!supported||!route.path("method").asText().equals("POST")||route.hasNonNull("auth")){
                if(!Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(route.path("method").asText())
                    ||!reviewed(profile,id))return null;
            }
            JsonNode target=null;
            for(var candidate:profile.path("destinations"))if(candidate.path("id").asText().equals(destination)){
                if(target!=null)return null;target=candidate;
            }
            if(target==null||!target.path("scheme").asText().equals("https")
                    ||!target.path("mode").asText().equals("BROKERED"))return null;
        }
        return new Scope(device,pool,image,Canonical.digest(runtime),Canonical.digest(descriptor),
                Canonical.digest(profile),profile.path("revision").asLong());
    }
    private static boolean brokerAuth(JsonNode descriptor,String id){
        int matches=0;
        for(var auth:descriptor.path("httpAuth"))if(auth.path("id").asText().equals(id)){
            if(!auth.path("type").asText().equals("API_KEY")||!auth.path("secret").isTextual()
                ||auth.path("secret").asText().isEmpty()||!auth.path("header").isTextual())return false;
            matches++;
        }
        return matches==1;
    }
    private static boolean reviewed(JsonNode profile,String route){
        int matches=0;
        for(var review:profile.path("effectReviews"))if(review.path("policy").path("routeId").asText().equals(route)){
            if(review.path("reviewedBy").asText().isBlank()||!review.path("policy").path("intent").isObject()
                ||!Set.of("READ","WRITE","FINANCIAL","DESTRUCTIVE").contains(review.path("policy").path("classification").asText()))
                return false;
            matches++;
        }
        return matches==1;
    }
    private static boolean arrayContains(JsonNode values,String expected){
        if(!values.isArray())return false;
        for(var value:values)if(value.isTextual()&&value.asText().equals(expected))return true;
        return false;
    }
}
