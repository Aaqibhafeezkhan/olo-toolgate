// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Version-1 fingerprint from trusted routing configuration and the accepted HTTP request.
 * Do not populate these inputs from an executor-submitted manifest or digest header. */
public final class HttpRequestFingerprint {
    private HttpRequestFingerprint() {}
    public record Profile(String id,String digest,String route,String authDeclarationId,List<String> credentialHeaders) {
        public Profile { credentialHeaders=List.copyOf(credentialHeaders); }
    }
    private static final Set<String> EXCLUDED=Set.of("host","content-length","transfer-encoding","connection",
        "user-agent","traceparent","tracestate","content-type");
    private static final Set<String> SEMANTIC=Set.of("host","accept","cookie","content-type","content-encoding",
        "content-length","transfer-encoding","connection","user-agent","traceparent","tracestate");
    /** URI uses the configured public destination origin and the actual raw request path/query.
     * Header values retain their wire order. Body is the exact accepted buffered bytes. */
    public static String digest(Profile profile,URI uri,String method,Map<String,List<String>> actualHeaders,byte[] body) {
        Objects.requireNonNull(profile);Objects.requireNonNull(body);
        if(profile.id()==null||profile.id().isEmpty()||profile.id().length()>128
            ||profile.route()==null||profile.route().isEmpty()||profile.route().length()>128
            ||profile.digest()==null||!profile.digest().matches("sha256:[a-f0-9]{64}"))throw invalid();
        if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
            ||!Set.of("https","http").contains(uri.getScheme())||!token(method))throw invalid();
        var credentials=new TreeSet<String>();
        for(var name:profile.credentialHeaders()){
            name=name.toLowerCase(Locale.ROOT);
            if(!token(name)||SEMANTIC.contains(name))throw invalid();
            credentials.add(name);
        }
        if(credentials.isEmpty()!=(profile.authDeclarationId()==null)
            ||profile.authDeclarationId()!=null&&profile.authDeclarationId().isEmpty())throw invalid();
        var headers=new TreeMap<String,Object>();var seen=new HashSet<String>();String contentType="";
        for(var entry:actualHeaders.entrySet()){
            String name=entry.getKey().toLowerCase(Locale.ROOT);
            if(!token(name)||!seen.add(name)||entry.getValue()==null||entry.getValue().isEmpty())throw invalid();
            for(var value:entry.getValue())if(value==null||value.chars().anyMatch(c->c!=9&&(c<32||c>126)))throw invalid();
            if(Set.of("authorization","proxy-authorization","cookie","toolgate-service-permit").contains(name)&&!credentials.contains(name))throw invalid();
            if(name.equals("content-type")){
                if(entry.getValue().size()!=1)throw invalid();
                contentType=entry.getValue().getFirst();
            }
            if(!credentials.contains(name)&&!EXCLUDED.contains(name))headers.put(name,List.copyOf(entry.getValue()));
        }
        int port=uri.getPort()<0?(uri.getScheme().equals("https")?443:80):uri.getPort();
        if(port<1||port>65535)throw invalid();
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        var manifest=new TreeMap<String,Object>();
        manifest.put("version","toolgate-http-request-v1");manifest.put("profileId",profile.id());
        manifest.put("profileDigest",profile.digest());manifest.put("routeId",profile.route());
        manifest.put("scheme",uri.getScheme());manifest.put("host",host);manifest.put("port",port);
        manifest.put("method",method.toUpperCase(Locale.ROOT));
        manifest.put("path",uri.getRawPath().isEmpty()?"/":uri.getRawPath());
        manifest.put("query",uri.getRawQuery()==null?"":uri.getRawQuery());manifest.put("contentType",contentType);
        manifest.put("bodyDigest",hash(body));manifest.put("headers",headers);
        manifest.put("authDeclarationId",profile.authDeclarationId());manifest.put("credentialHeaders",new ArrayList<>(credentials));
        var out=new StringBuilder();jcs(out,manifest);return hash(out.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static boolean token(String value){
        return value!=null&&!value.isEmpty()&&value.chars().allMatch(c->c>32&&c<127&&"()<>@,;:\\\"/[]?={} ".indexOf(c)<0);
    }
    private static String hash(byte[] bytes){
        try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    // The manifest subset has only strings, integral ports, null, arrays and objects.
    // Sort UTF-16 keys; preserve string code points without NFC, as RFC 8785 requires.
    private static void jcs(StringBuilder out,Object value){
        if(value==null){out.append("null");return;}
        if(value instanceof String text){string(out,text);}
        else if(value instanceof Integer number)out.append(number);
        else if(value instanceof List<?> list){
            out.append('[');boolean first=true;for(var item:list){if(!first)out.append(',');first=false;jcs(out,item);}out.append(']');
        }else if(value instanceof Map<?,?> map){
            var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put((String)k,v));
            out.append('{');boolean first=true;for(var e:sorted.entrySet()){if(!first)out.append(',');first=false;jcs(out,e.getKey());out.append(':');jcs(out,e.getValue());}out.append('}');
        }else throw invalid();
    }
    private static void string(StringBuilder out, String raw) {
        String s = raw;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid actual HTTP request facts");}
}
