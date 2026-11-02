// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Verifies issuer-signed admission facts and immutable Control-owned profile data before issuing service grants. */
public final class ServiceRequestValidation {
    private ServiceRequestValidation() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FORBIDDEN=Set.of("authorization","toolgate-service-permit","proxy-authorization","cookie",
        "host","forwarded","content-length","transfer-encoding","connection","te","upgrade","user-agent","traceparent","tracestate","content-type");

    public static void validate(Store.Session tx,Codec codec,EffectSigner signer,String issuer,EnterpriseInvocation invocation,
                                String device,ServicePermitIssue request,long now){
        var manifest=request.httpRequestManifest();
        var lease=tx.enterprise().executionLease(invocation.id());
        if(request.httpRequestDigest()==null){
            if(lease!=null||manifest!=null||request.invocationPermit()!=null||request.brokerInstanceId()!=null||request.handlerId()!=null)throw ManagementAccess.denied();
            return;
        }
        if(manifest==null||request.invocationPermit()==null||request.brokerInstanceId()==null||request.handlerId()==null||lease==null||lease.expiresAt()<=now
            ||!lease.deviceId().equals(device)||!lease.fencingToken().equals(invocation.reservedNonce()))throw ManagementAccess.denied();
        var profile=admission(tx,codec,signer,issuer,invocation,device,request.invocationPermit(),manifest,now);
        validateManifest(profile,manifest,request.audience(),invocation.evaluation().action(),"sha256:"+invocation.evaluation().argumentsDigest());
        if(!Canonical.arguments(tree(codec.json(manifest))).equals(request.httpRequestDigest()))throw ManagementAccess.denied();
    }

    /** Shared signed admission/profile verification. The caller must check current authority and the live lease. */
    public static ServiceProfile admission(Store.Session tx,Codec codec,EffectSigner signer,String issuer,EnterpriseInvocation invocation,
            String device,io.ololabs.toolgate.contracts.v2.SignedInvocationPermit proof,PreparedHttpRequestManifest manifest,long now){
        var claims=signer.verifyInvocation(proof);
        var e=invocation.evaluation();var c=e.context();var identity=claims.identity();var target=claims.target();
        if(!issuer.equals(claims.iss())||!device.equals(claims.aud())||!invocation.id().equals(claims.invocationId())
            ||!invocation.reservedNonce().equals(claims.nonce())||claims.iat()>now||claims.nbf()>now
            ||claims.exp()<=claims.iat()||claims.deadlineUnixMs()<=now||claims.deadlineUnixMs()<invocation.expiresAtUnixMs()
            ||!identity.tenantId().equals(c.tenantId())||identity.mode()!=c.mode()
            ||!Objects.equals(identity.userId(),c.userId())||!Objects.equals(identity.agentId(),c.agentId())
            ||!identity.authorizationEpoch().equals(invocation.authorizationEpoch())
            ||!e.toolId().equals(target.toolId())||!("sha256:"+e.packageDigest()).equals(target.packageDigest())
            ||!e.action().equals(claims.action())
            ||!tree(codec.json(e.resources())).equals(tree(codec.json(claims.resources()))))throw ManagementAccess.denied();
        // The issuer bound the signed JCS argument digest at admission. The consumed nonce binds this
        // invocation; its legacy exact-body fingerprint below is a different digest domain (e.g. 1.0 vs 1).
        // exp is only the already-consumed admission window. The caller checks current execution authority.
        String raw=tx.packages().document(target.serviceProfileDigest());
        if(raw==null||!Canonical.digest(tree(raw)).equals(target.serviceProfileDigest()))throw ManagementAccess.denied();
        var profile=codec.model(raw,ServiceProfile.class);
        if(!profile.profileId().equals(manifest.profileId())||!target.serviceProfileDigest().equals(manifest.profileDigest())
            ||!profile.revision().equals(target.serviceProfileRevision())||!target.routes().contains(manifest.routeId()))
            throw ManagementAccess.denied();
        return profile;
    }

    /** profile must already have been loaded by a verified signed digest. */
    public static void validateManifest(ServiceProfile profile,PreparedHttpRequestManifest m,ServiceAudience audience,
                                        String operation,String admittedBodyDigest){
        var routes=profile.routes().stream().filter(r->r.id().equals(m.routeId())).toList();
        if(routes.size()!=1)throw ManagementAccess.denied();var route=routes.getFirst();
        var destinations=profile.destinations().stream().filter(d->d.id().equals(route.destination())).toList();
        if(destinations.size()!=1)throw ManagementAccess.denied();var destination=destinations.getFirst();
        String service=audience.name().replace('_','-');
        String host=destination.host().toLowerCase(Locale.ROOT);
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(!m.version().equals("toolgate-http-request-v1")||!m.profileId().equals(profile.profileId())
            ||!route.destination().equals(service)||destination.scheme()!=DestinationScheme.https
            ||destination.mode()!=EgressMode.BROKERED||route.auth()!=null||!m.scheme().equals("https")
            ||!m.host().equals(host)||!m.port().equals(destination.port())||!m.method().equals(route.method().name())
            ||!m.path().equals("/v1/"+operation)||!pathMatches(route.path(),m.path())
            ||!m.bodyDigest().equals(admittedBodyDigest)||!m.credentialHeaders().equals(List.of("toolgate-service-permit"))
            ||!m.authDeclarationId().isTextual()||!m.authDeclarationId().asText().equals("toolgate-service-permit:"+service))
            throw ManagementAccess.denied();
        String media=m.contentType().split(";",2)[0];
        if(!media.equals(media.strip())||route.requestContentTypes().stream().noneMatch(t->t.equalsIgnoreCase(media)))
            throw ManagementAccess.denied();
        String previous="";
        if(!m.query().isEmpty())for(var pair:m.query().split("&",-1)){
            int equals=pair.indexOf('=');if(equals<1)throw ManagementAccess.denied();
            String name=pair.substring(0,equals),decoded=decode(name);
            if(name.compareTo(previous)<0||!encode(decoded).equals(name)||!route.query().contains(decoded))throw ManagementAccess.denied();
            String encodedValue=pair.substring(equals+1);
            if(!encode(decode(encodedValue)).equals(encodedValue))throw ManagementAccess.denied();previous=name;
        }
        for(var header:m.headers().entrySet()){
            String name=header.getKey();var values=header.getValue();
            if(!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")||FORBIDDEN.contains(name)||name.startsWith("x-forwarded-")
                ||!values.isArray()||values.size()!=1||!values.get(0).isTextual()
                ||values.get(0).asText().chars().anyMatch(v->v!=9&&(v<32||v>126)))throw ManagementAccess.denied();
            boolean declared=route.headers().stream().anyMatch(h->h.equalsIgnoreCase(name));
            if(!declared&&!(name.equals("accept")&&values.get(0).asText().equals("*/*")))throw ManagementAccess.denied();
        }
        if(!m.headers().containsKey("accept"))throw ManagementAccess.denied();
    }

    static boolean pathMatches(String template,String actual){
        String[] expected=template.split("/",-1),given=actual.split("/",-1);
        if(expected.length!=given.length)return false;
        for(int n=0;n<expected.length;n++){
            String part=expected[n];if(!part.startsWith("{")){if(!part.equals(given[n]))return false;continue;}
            if(!part.endsWith("}"))return false;
            String inner=part.substring(1,part.length()-1);int colon=inner.indexOf(':');
            String name=colon<0?inner:inner.substring(0,colon),constraint=colon<0?null:inner.substring(colon+1);
            if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))return false;
            // Restrict to a single linear character class; reject arbitrary backtracking patterns.
            if(constraint!=null&&!constraint.matches("\\[[A-Za-z0-9_.\\-]+\\](?:[+*?]|\\{[0-9]{1,3}(?:,[0-9]{0,3})?\\})?"))return false;
            String value=decode(given[n]),lower=value.toLowerCase(Locale.ROOT);
            if(value.isEmpty()||value.equals(".")||value.equals("..")||value.contains("/")||value.contains("\\")
                ||lower.contains("%2f")||lower.contains("%5c")||lower.contains("%2e")||!encode(value).equals(given[n]))return false;
            try{if(constraint!=null&&!java.util.regex.Pattern.matches(constraint,value))return false;}
            catch(java.util.regex.PatternSyntaxException invalid){return false;}
        }return true;
    }
    private static String decode(String value){
        try{
            var out=new java.io.ByteArrayOutputStream();
            for(int n=0;n<value.length();n++){char c=value.charAt(n);if(c=='%'){
                if(n+2>=value.length())throw ManagementAccess.denied();
                int a=Character.digit(value.charAt(++n),16),b=Character.digit(value.charAt(++n),16);
                if(a<0||b<0)throw ManagementAccess.denied();out.write((a<<4)|b);
            }else{if(c>126||c<33)throw ManagementAccess.denied();out.write(c);}}
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        }catch(CharacterCodingException invalid){throw ManagementAccess.denied();}
    }
    private static String encode(String value){
        var out=new StringBuilder();for(byte b:value.getBytes(StandardCharsets.UTF_8)){
            int c=b&255;if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"-._~".indexOf(c)>=0)out.append((char)c);
            else out.append('%').append("0123456789ABCDEF".charAt(c>>4)).append("0123456789ABCDEF".charAt(c&15));
        }return out.toString();
    }
    private static JsonNode tree(String document){
        try{return JSON.readTree(document);}catch(java.io.IOException invalid){throw ManagementAccess.denied();}
    }
}
