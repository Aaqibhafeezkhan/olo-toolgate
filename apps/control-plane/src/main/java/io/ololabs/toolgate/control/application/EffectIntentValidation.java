// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.contracts.PreparedHttpRequestManifest;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Reconstructs intent from reviewed constants and signed admission arguments, never worker-selected digests. */
public final class EffectIntentValidation {
    private EffectIntentValidation() {}
    private static final JsonNodeFactory JSON=JsonNodeFactory.instance;
    private static final int MAX_DEPTH=32, MAX_NODES=8192, MAX_BYTES=2*1024*1024;
    private static final Set<String> FORBIDDEN=Set.of("authorization","proxy-authorization","cookie","host",
        "forwarded","content-length","transfer-encoding","connection","te","upgrade","user-agent",
        "traceparent","tracestate","content-type","toolgate-service-permit");
    public static boolean reserved(String destination){return Set.of("olo-memory","olo-rag").contains(destination);}
    /** High-risk classes cannot opt out of explicit user/effect consent through a deployment flag. */
    public static boolean requiresConsent(EffectClassification classification,boolean declared){
        return declared||classification==EffectClassification.FINANCIAL||classification==EffectClassification.DESTRUCTIVE;
    }

    /** Reject invalid review documents before making them immutable profile authority. */
    public static void review(RouteEffectPolicy policy,RouteDeclaration route) {
        if(!policy.routeId().equals(route.id())||reserved(route.destination())
            ||route.streaming()!=Streaming.NONE)throw Failure.validation();
        if((policy.classification()==EffectClassification.DESTRUCTIVE||policy.classification()==EffectClassification.FINANCIAL)
            && !policy.acknowledgedUnfencedRisk())throw Failure.validation();
        var intent=policy.intent();
        var count=new int[]{0};
        for(JsonNode template:List.of(intent.path(),JSON.objectNode().setAll(intent.query()),
                JSON.objectNode().setAll(intent.headers()),intent.body()))checkTemplate(template,0,count);
        if(!intent.path().isTextual()&&!reference(intent.path()))throw Failure.validation();
        for(String name:intent.query().keySet())if(!route.query().contains(name))throw Failure.validation();
        for(String name:intent.headers().keySet()){
            if(!header(name)||FORBIDDEN.contains(name)||name.startsWith("x-forwarded-")
                ||(!name.equals("accept")&&route.headers().stream().noneMatch(h->h.equalsIgnoreCase(name))))throw Failure.validation();
        }
        if(intent.bodyMode()==EffectBodyMode.NONE){
            if(!intent.body().isNull()||!intent.contentType().isEmpty())throw Failure.validation();
        }else if(route.requestContentTypes().stream().noneMatch(t->t.equalsIgnoreCase(intent.contentType()))
            ||!intent.contentType().equals("application/json"))throw Failure.validation();
    }

    private static RouteEffectReview reviewed(ServiceProfile profile,String routeId){
        var routes=profile.routes().stream().filter(r->r.id().equals(routeId)).toList();
        if(routes.size()!=1||profile.effectReviews()==null)throw ManagementAccess.denied();
        var reviews=profile.effectReviews().stream().filter(r->r.policy().routeId().equals(routeId)).toList();
        if(reviews.size()!=1||reviews.getFirst().reviewedBy().isBlank())throw ManagementAccess.denied();
        try{review(reviews.getFirst().policy(),routes.getFirst());}catch(Failure invalid){throw ManagementAccess.denied();}
        return reviews.getFirst();
    }
    /** Preview construction grants no execution authority, including for high-risk policies. */
    public static EffectConsentMappedRequest preview(ServiceProfile profile,String routeId,JsonNode arguments){
        boundedArguments(arguments);
        var policy=reviewed(profile,routeId).policy();
        var route=profile.routes().stream().filter(r->r.id().equals(routeId)).findFirst().orElseThrow(ManagementAccess::denied);
        var destinations=profile.destinations().stream().filter(d->d.id().equals(route.destination())).toList();
        if(destinations.size()!=1)throw ManagementAccess.denied();
        var destination=destinations.getFirst();var intent=policy.intent();
        if(destination.scheme()!=DestinationScheme.https||destination.mode()!=EgressMode.BROKERED)throw ManagementAccess.denied();
        var budget=new int[]{0};
        var path=resolve(intent.path(),arguments,0,budget);
        if(!path.isTextual()||path.textValue().length()>4096||!ServiceRequestValidation.pathMatches(route.path(),path.textValue()))throw ManagementAccess.denied();
        var query=new TreeMap<String,String>();int queryBytes=0;
        for(var entry:intent.query().entrySet()){
            var value=resolve(entry.getValue(),arguments,0,budget);
            if(!value.isTextual())throw ManagementAccess.denied();
            String key=encode(entry.getKey()),encoded=encode(value.textValue());
            queryBytes+=key.length()+1+encoded.length()+(query.isEmpty()?0:1);
            if(queryBytes>8192)throw ManagementAccess.denied();
            query.put(key,encoded);
        }
        var queryString=new StringJoiner("&");query.forEach((k,v)->queryString.add(k+"="+v));
        var headers=new TreeMap<String,JsonNode>();var headerBudget=new long[]{MAX_BYTES};
        intent.headers().forEach((name,template)->{
            var value=resolve(template,arguments,0,budget);
            if(!value.isTextual()||value.textValue().length()>8192||value.textValue().chars().anyMatch(c->c!=9&&(c<32||c>126)))throw ManagementAccess.denied();
            spend(headerBudget,(long)name.length()+value.textValue().length()+8);
            headers.put(name,JSON.arrayNode().add(value.textValue()));
        });
        headers.putIfAbsent("accept",JSON.arrayNode().add("*/*"));
        JsonNode bodyValue=intent.bodyMode()==EffectBodyMode.NONE?JSON.nullNode():resolve(intent.body(),arguments,0,budget);
        if(intent.bodyMode()!=EffectBodyMode.NONE)jsonBytes(bodyValue,new long[]{Math.min(MAX_BYTES,route.maxRequestBytes())});
        byte[] body=intent.bodyMode()==EffectBodyMode.NONE?new byte[0]:Canonical.jcs(bodyValue);
        if(body.length>MAX_BYTES||body.length>route.maxRequestBytes())throw ManagementAccess.denied();
        String host=destination.host().toLowerCase(Locale.ROOT);
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        return new EffectConsentMappedRequest(EffectConsentMappingKind.MAPPED_HTTP,routeId,policy.classification(),
            "https",host,destination.port(),route.method().name(),path.textValue(),queryString.toString(),
            headers,intent.contentType(),intent.bodyMode(),bodyValue);
    }
    /** Profile and admitted argument preimage must already have been independently authenticated. */
    public static RouteEffectPolicy validate(ServiceProfile profile,PreparedHttpRequestManifest manifest,JsonNode arguments) {
        var policy=match(profile,manifest,arguments);
        if(requiresConsent(policy.classification(),policy.consentRequired()))throw ManagementAccess.denied();
        return policy;
    }
    /** Internal effect issuer must additionally reserve exact consent for a required policy in the same transaction. */
    static RouteEffectPolicy match(ServiceProfile profile,PreparedHttpRequestManifest manifest,JsonNode arguments){
        var policy=reviewed(profile,manifest.routeId()).policy();
        var expected=preview(profile,manifest.routeId(),arguments);
        byte[] body=expected.bodyMode()==EffectBodyMode.NONE?new byte[0]:Canonical.jcs(expected.body());
        if(!manifest.version().equals("toolgate-http-request-v1")
            ||!manifest.profileId().equals(profile.profileId())||!manifest.profileDigest().equals(Canonical.digest(profileNode(profile)))
            ||!manifest.scheme().equals(expected.scheme())||!manifest.host().equals(expected.host())||!manifest.port().equals(expected.port())
            ||!manifest.method().equals(expected.method())||!manifest.path().equals(expected.path())
            ||!manifest.query().equals(expected.query())||!manifest.headers().equals(expected.headers())
            ||!manifest.contentType().equals(expected.contentType())||!manifest.bodyDigest().equals(Canonical.sha256(body)))
            throw ManagementAccess.denied();
        // Credential header names are checked against the package-owned auth declaration by the issuer.
        var route=profile.routes().stream().filter(r->r.id().equals(manifest.routeId())).findFirst().orElseThrow(ManagementAccess::denied);
        var auth=manifest.authDeclarationId();
        if(route.auth()==null? !auth.isNull()||!manifest.credentialHeaders().isEmpty():
            !auth.isTextual()||!route.auth().equals(auth.textValue()))throw ManagementAccess.denied();
        return policy;
    }
    private static JsonNode profileNode(ServiceProfile profile){
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(profile);
    }
    private static boolean reference(JsonNode node){return node.isObject()&&node.size()==1&&node.has("$arg");}
    private static void checkTemplate(JsonNode node,int depth,int[] count){
        if(node==null||depth>MAX_DEPTH||++count[0]>MAX_NODES)throw Failure.validation();
        if(node.isTextual()&&!Normalizer.isNormalized(node.textValue(),Normalizer.Form.NFC))throw Failure.validation();
        if(reference(node)){
            var pointer=node.get("$arg");
            if(!pointer.isTextual()||!validPointer(pointer.textValue())
                ||!Normalizer.isNormalized(pointer.textValue(),Normalizer.Form.NFC))throw Failure.validation();
            return;
        }
        if(node.isObject())node.properties().forEach(e->{
            if(e.getKey().startsWith("$")||!Normalizer.isNormalized(e.getKey(),Normalizer.Form.NFC))throw Failure.validation();
            checkTemplate(e.getValue(),depth+1,count);
        });
        else if(node.isArray())for(var item:node)checkTemplate(item,depth+1,count);
    }
    private static boolean validPointer(String value){
        if(value.length()>1024||(!value.isEmpty()&&!value.startsWith("/")))return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)=='~'){
            if(++i>=value.length()||value.charAt(i)!='0'&&value.charAt(i)!='1')return false;
        }
        return true;
    }
    private static JsonNode resolve(JsonNode node,JsonNode arguments,int depth,int[] budget){
        if(depth>MAX_DEPTH||++budget[0]>MAX_NODES)throw ManagementAccess.denied();
        if(reference(node)){
            var value=arguments.at(node.get("$arg").textValue());
            if(value.isMissingNode())throw ManagementAccess.denied();
            // Bound referenced data as data. Never recursively interpret its $arg-looking members.
            checkData(value,depth,budget);
            return value.deepCopy();
        }
        if(node.isObject()){
            var result=JSON.objectNode();node.properties().forEach(e->result.set(e.getKey(),resolve(e.getValue(),arguments,depth+1,budget)));return result;
        }
        if(node.isArray()){
            var result=JSON.arrayNode();for(var item:node)result.add(resolve(item,arguments,depth+1,budget));return result;
        }
        return node.deepCopy();
    }
    static void boundedArguments(JsonNode arguments){
        if(arguments==null||!arguments.isObject())throw ManagementAccess.denied();
        checkData(arguments,0,new int[]{0});
        if(Canonical.jcs(arguments).length>MAX_BYTES)throw ManagementAccess.denied();
    }
    private static void checkData(JsonNode node,int depth,int[] budget){
        if(depth>MAX_DEPTH||++budget[0]>MAX_NODES)throw ManagementAccess.denied();
        if(node.isContainerNode())for(var child:node)checkData(child,depth+1,budget);
    }
    private static boolean header(String value){return value.matches("[!#$%&'*+.^_"+(char)96+"|~0-9a-z-]+");}
    private static void spend(long[] budget,long bytes){budget[0]-=bytes;if(budget[0]<0)throw ManagementAccess.denied();}
    /** Bound expansion before serializing a container; repeated references cannot create an oversized intermediate string. */
    private static void jsonBytes(JsonNode value,long[] budget){
        if(value.isObject()){
            spend(budget,2);boolean first=true;
            for(var entry:value.properties()){
                if(!first)spend(budget,1);first=false;
                spend(budget,Canonical.jcs(JSON.textNode(entry.getKey())).length+1L);
                jsonBytes(entry.getValue(),budget);
            }
        }else if(value.isArray()){
            spend(budget,2);boolean first=true;
            for(var child:value){if(!first)spend(budget,1);first=false;jsonBytes(child,budget);}
        }else spend(budget,Canonical.jcs(value).length);
    }
    private static String encode(String value){
        var out=new StringBuilder();
        for(byte b:value.getBytes(StandardCharsets.UTF_8)){
            int c=b&255;
            if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"-._~".indexOf(c)>=0)out.append((char)c);
            else out.append('%').append("0123456789ABCDEF".charAt(c>>4)).append("0123456789ABCDEF".charAt(c&15));
            if(out.length()>8192)throw ManagementAccess.denied();
        }
        return out.toString();
    }
}
