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

    /** Profile and admitted argument preimage must already have been independently authenticated. */
    public static RouteEffectPolicy validate(ServiceProfile profile,PreparedHttpRequestManifest manifest,JsonNode arguments) {
        if(arguments==null||!arguments.isObject())throw ManagementAccess.denied();
        var routes=profile.routes().stream().filter(r->r.id().equals(manifest.routeId())).toList();
        if(routes.size()!=1||profile.effectReviews()==null)throw ManagementAccess.denied();
        var route=routes.getFirst();
        var reviews=profile.effectReviews().stream().filter(r->r.policy().routeId().equals(route.id())).toList();
        if(reviews.size()!=1)throw ManagementAccess.denied();
        var reviewed=reviews.getFirst();var policy=reviewed.policy();
        // Consent authority is deliberately not inferred from invocation approval.
        if(policy.consentRequired()||reviewed.reviewedBy().isBlank())throw ManagementAccess.denied();
        try{review(policy,route);}catch(Failure invalid){throw ManagementAccess.denied();}
        var destinations=profile.destinations().stream().filter(d->d.id().equals(route.destination())).toList();
        if(destinations.size()!=1)throw ManagementAccess.denied();
        var destination=destinations.getFirst();var intent=policy.intent();
        var budget=new int[]{0};
        var path=resolve(intent.path(),arguments,0,budget);
        if(!path.isTextual()||!ServiceRequestValidation.pathMatches(route.path(),path.textValue()))throw ManagementAccess.denied();
        var query=new TreeMap<String,String>();
        for(var entry:intent.query().entrySet()){
            var value=resolve(entry.getValue(),arguments,0,budget);
            if(!value.isTextual())throw ManagementAccess.denied();
            query.put(encode(entry.getKey()),encode(value.textValue()));
        }
        var queryString=new StringJoiner("&");query.forEach((k,v)->queryString.add(k+"="+v));
        var headers=new TreeMap<String,JsonNode>();
        intent.headers().forEach((name,template)->{
            var value=resolve(template,arguments,0,budget);
            if(!value.isTextual()||value.textValue().chars().anyMatch(c->c!=9&&(c<32||c>126)))throw ManagementAccess.denied();
            headers.put(name,JSON.arrayNode().add(value.textValue()));
        });
        headers.putIfAbsent("accept",JSON.arrayNode().add("*/*"));
        byte[] body=intent.bodyMode()==EffectBodyMode.NONE?new byte[0]:Canonical.jcs(resolve(intent.body(),arguments,0,budget));
        if(body.length>MAX_BYTES||body.length>route.maxRequestBytes())throw ManagementAccess.denied();
        String host=destination.host().toLowerCase(Locale.ROOT);
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(!manifest.version().equals("toolgate-http-request-v1")
            ||!manifest.profileId().equals(profile.profileId())||!manifest.profileDigest().equals(Canonical.digest(profileNode(profile)))
            ||destination.scheme()!=DestinationScheme.https||destination.mode()!=EgressMode.BROKERED
            ||!manifest.scheme().equals("https")||!manifest.host().equals(host)||!manifest.port().equals(destination.port())
            ||!manifest.method().equals(route.method().name())||!manifest.path().equals(path.textValue())
            ||!manifest.query().equals(queryString.toString())||!manifest.headers().equals(headers)
            ||!manifest.contentType().equals(intent.contentType())||!manifest.bodyDigest().equals(Canonical.sha256(body)))
            throw ManagementAccess.denied();
        // Credential header names are checked against the package-owned auth declaration by the issuer.
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
    private static void checkData(JsonNode node,int depth,int[] budget){
        if(depth>MAX_DEPTH||++budget[0]>MAX_NODES)throw ManagementAccess.denied();
        if(node.isContainerNode())for(var child:node)checkData(child,depth+1,budget);
    }
    private static boolean header(String value){return value.matches("[!#$%&'*+.^_"+(char)96+"|~0-9a-z-]+");}
    private static String encode(String value){
        var out=new StringBuilder();
        for(byte b:value.getBytes(StandardCharsets.UTF_8)){
            int c=b&255;
            if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"-._~".indexOf(c)>=0)out.append((char)c);
            else out.append('%').append("0123456789ABCDEF".charAt(c>>4)).append("0123456789ABCDEF".charAt(c&15));
        }
        return out.toString();
    }
}
