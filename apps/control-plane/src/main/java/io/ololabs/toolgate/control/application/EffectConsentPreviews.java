// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ResourceDescriptor;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reconstructs immutable preview bytes. Calling this factory never approves or reserves an effect. */
public final class EffectConsentPreviews {
    private EffectConsentPreviews(){}
    private static final int MAX_PREVIEW_BYTES=1024*1024;
    private static final java.math.BigDecimal MAX_DISPLAY_NUMBER=java.math.BigDecimal.valueOf(9007199254740991L);
    /** Supplied only after authenticating the original admission; deadlines are its original limits. */
    public record Scope(String tenantId,String operationId,String runId,String toolId,String action,
            String argumentsDigest,List<ResourceDescriptor> resources,long operationDeadline,Long credentialDeadline){
        public Scope {resources=List.copyOf(resources);}
    }
    /** Strings retain an immutable snapshot even if an input model or returned decoded preview is subsequently changed. */
    public record Snapshot(String document,String digest,long expiresAtUnixMs){
        public EffectConsentPreview preview(Codec codec){return codec.model(document,EffectConsentPreview.class);}
    }
    public static Snapshot generic(Codec codec,Scope scope,ServiceProfile profile,JsonNode arguments,
            Map<String,Long> routeCounts,long now){
        arguments(scope,arguments,now);
        if(routeCounts==null||routeCounts.isEmpty()||routeCounts.size()>64)throw Failure.validation();
        var slots=new ArrayList<EffectConsentSlot>();long budget=MAX_PREVIEW_BYTES;
        for(var entry:new TreeMap<>(routeCounts).entrySet()){
            var request=EffectIntentValidation.preview(profile,entry.getKey(),arguments);
            budget-=codec.json(request).getBytes(StandardCharsets.UTF_8).length+64L;
            if(budget<0)throw Failure.validation();
            slots.add(slot(slots.size(),request,entry.getValue()));
        }
        return seal(codec,scope,profile,slots,now);
    }
    public static Snapshot nativeMcp(Codec codec,Scope scope,ServiceProfile profile,McpProxyDocument document,
            JsonNode arguments,long permittedCount,long now){
        arguments(scope,arguments,now);
        var request=McpEffectValidation.preview(profile,document,scope.toolId(),arguments);
        return seal(codec,scope,profile,List.of(slot(0,request,permittedCount)),now);
    }
    private static EffectConsentSlot slot(int index,EffectConsentMappedRequest request,Long count){
        if(count==null||count<1||count>64)throw Failure.validation();
        return new EffectConsentSlot((long)index,request,count);
    }
    private static void arguments(Scope scope,JsonNode arguments,long now){
        EffectIntentValidation.boundedArguments(arguments);
        if(now<0||now>=scope.operationDeadline()||scope.credentialDeadline()!=null&&now>=scope.credentialDeadline()
            ||!Canonical.arguments(arguments).equals(scope.argumentsDigest()))throw ManagementAccess.denied();
    }
    private static Snapshot seal(Codec codec,Scope scope,ServiceProfile profile,List<EffectConsentSlot> slots,long now){
        long expiry=scope.credentialDeadline()==null?scope.operationDeadline():Math.min(scope.operationDeadline(),scope.credentialDeadline());
        var profileNode=(JsonNode)codec.value(codec.json(profile));
        var preview=new EffectConsentPreview("consent-"+UUID.randomUUID(),scope.tenantId(),scope.operationId(),scope.runId(),
            scope.toolId(),scope.action(),scope.argumentsDigest(),scope.resources(),Canonical.digest(profileNode),now,expiry,slots);
        // Decode against canonical schema before exposing the sealed document, including all identifier/resource bounds.
        String document=codec.json(preview);
        if(document.getBytes(StandardCharsets.UTF_8).length>MAX_PREVIEW_BYTES)throw Failure.validation();
        codec.model(document,EffectConsentPreview.class);
        // Argument data is not NFC-normalized. Consent binds the exact resolved values, just like request fingerprints.
        var value=(JsonNode)codec.value(document);
        displaySafe(value);
        byte[] canonical=Canonical.jcs(value);
        // Leave headroom for the typed review envelope under the console 2 MiB response cap.
        if(canonical.length>MAX_PREVIEW_BYTES)throw Failure.validation();
        return new Snapshot(new String(canonical,StandardCharsets.UTF_8),Canonical.sha256(canonical),expiry);
    }
    private static void displaySafe(JsonNode value){
        // The browser must display the exact numeric recipient/amount that the preview approves.
        if(value.isNumber()&&value.decimalValue().abs().compareTo(MAX_DISPLAY_NUMBER)>0)throw Failure.validation();
        if(value.isContainerNode())for(var child:value)displaySafe(child);
    }

}
