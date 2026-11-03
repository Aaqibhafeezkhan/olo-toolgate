// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.ololabs.toolgate.contracts.PreparedHttpRequestManifest;
import io.ololabs.toolgate.contracts.v2.*;
import java.net.URI;
import java.util.*;

/** Independent native tools/call intent reconstruction. Opaque protocol context cannot select business arguments or target. */
public final class McpEffectValidation {
    private McpEffectValidation() {}
    private static final JsonNodeFactory JSON=JsonNodeFactory.instance;
    public static String route(String toolId){return "mcp-"+DirectoryService.digest(toolId).substring(0,32);}
    public static void review(McpEffectPolicy policy){
        if((policy.classification()==EffectClassification.DESTRUCTIVE||policy.classification()==EffectClassification.FINANCIAL)
            &&!policy.acknowledgedUnfencedRisk())throw Failure.validation();
    }
    public static McpEffectPolicy validate(ServiceProfile profile,McpProxyDocument document,String toolId,
            PreparedHttpRequestManifest manifest,JsonNode arguments,String handlerId){
        if(profile.nativeMcpEffects()==null||arguments==null||!arguments.isObject()
            ||!profile.packageId().equals("mcp."+document.server().slug())
            ||!toolId.startsWith(profile.packageId()+"/"))throw ManagementAccess.denied();
        var reviews=profile.nativeMcpEffects().stream().filter(r->r.toolId().equals(toolId)).toList();
        var tools=document.tools().stream().filter(t->t.toolId().equals(toolId)).toList();
        if(reviews.size()!=1||tools.size()!=1)throw ManagementAccess.denied();
        var reviewed=reviews.getFirst();var tool=tools.getFirst();var policy=reviewed.policy();
        if(reviewed.reviewedBy().isBlank()||policy.consentRequired()||!policy.equals(tool.effectPolicy()))throw ManagementAccess.denied();
        try{review(policy);}catch(Failure invalid){throw ManagementAccess.denied();}
        URI url;
        try{url=new URI(new URI(document.server().url()).toASCIIString()).normalize();}
        catch(java.net.URISyntaxException invalid){throw ManagementAccess.denied();}
        if(!"https".equalsIgnoreCase(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getFragment()!=null)
            throw ManagementAccess.denied();
        String host=url.getHost().toLowerCase(Locale.ROOT);
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        String path=url.getRawPath();if(path==null||path.isEmpty())path="/";
        var query=new ArrayList<String>();
        if(url.getRawQuery()!=null)for(String pair:url.getRawQuery().split("&",-1))if(!pair.isEmpty())query.add(pair);
        query.sort(Comparator.comparing(pair->pair.split("=",2)[0]));
        var headers=new TreeMap<String,JsonNode>();
        headers.put("accept",JSON.arrayNode().add("application/json, text/event-stream"));
        var protocol=manifest.headers().get("mcp-protocol-version");
        if(!one(protocol,32)||!protocol.get(0).asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw ManagementAccess.denied();
        headers.put("mcp-protocol-version",protocol);
        var session=manifest.headers().get("mcp-session-id");
        if(session!=null){if(!one(session,1024))throw ManagementAccess.denied();headers.put("mcp-session-id",session);}
        var body=JSON.objectNode().put("jsonrpc","2.0").put("id",handlerId).put("method","tools/call");
        body.putObject("params").put("name",tool.upstreamName()).set("arguments",arguments);
        var auth=document.server().auth();
        String credential=auth.scheme()==McpAuthScheme.HEADER?auth.header():"authorization";
        if(credential==null||credential.isBlank())throw ManagementAccess.denied();
        credential=credential.toLowerCase(Locale.ROOT);
        if(headers.containsKey(credential))throw ManagementAccess.denied();
        if(!manifest.version().equals("toolgate-http-request-v1")||!manifest.profileId().equals(profile.profileId())
            ||!manifest.profileDigest().equals(Canonical.digest(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(profile)))
            ||!manifest.routeId().equals(route(toolId))||!manifest.scheme().equals("https")||!manifest.host().equals(host)
            ||manifest.port()!=(url.getPort()<0?443:url.getPort())||!manifest.method().equals("POST")
            ||!manifest.path().equals(path)||!manifest.query().equals(String.join("&",query))
            ||!manifest.contentType().equals("application/json")||!manifest.headers().equals(headers)
            ||!manifest.bodyDigest().equals(Canonical.arguments(body))
            ||!manifest.credentialHeaders().equals(List.of(credential))
            ||!manifest.authDeclarationId().isTextual()
            ||!manifest.authDeclarationId().asText().equals("toolgate-mcp-upstream:"+document.server().slug()))
            throw ManagementAccess.denied();
        return policy;
    }
    private static boolean one(JsonNode values,int limit){
        return values!=null&&values.isArray()&&values.size()==1&&values.get(0).isTextual()
            &&!values.get(0).asText().isEmpty()&&values.get(0).asText().length()<=limit
            &&values.get(0).asText().chars().allMatch(c->c>=33&&c<=126);
    }
}
