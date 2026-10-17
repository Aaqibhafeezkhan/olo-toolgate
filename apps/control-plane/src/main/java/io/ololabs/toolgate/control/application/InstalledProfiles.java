// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Deployment facts are pinned independently of all group permission mappings. */
public final class InstalledProfiles {
    private static final ObjectMapper JSON=new ObjectMapper();
    private InstalledProfiles() {}
    private static JsonNode sorted(JsonNode value) {
        if(value.isObject()){var result=JSON.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(var key:keys)result.set(key,sorted(value.get(key)));return result;}
        if(value.isArray()){var result=JSON.createArrayNode();value.forEach(item->result.add(sorted(item)));return result;}return value;
    }
    public static String packageDigest(LocalToolRegistration tool,ManagedRuntime runtime) {
        var registration=JSON.valueToTree(tool);((ObjectNode)registration).remove("authorizationProfile");
        var manifest=JSON.createObjectNode();manifest.set("registration",registration);manifest.set("runtime",JSON.valueToTree(runtime));
        try{return DirectoryService.digest(JSON.writeValueAsBytes(sorted(manifest)));}catch(java.io.IOException failure){throw Failure.validation();}
    }
    /** The same digest for a registration being assembled, before it carries its authorization profile. */
    public static String packageDigest(JsonNode registration,JsonNode runtime) {
        var copy=(ObjectNode)registration.deepCopy();copy.remove("authorizationProfile");
        var manifest=JSON.createObjectNode();manifest.set("registration",copy);manifest.set("runtime",runtime);
        try{return DirectoryService.digest(JSON.writeValueAsBytes(sorted(manifest)));}catch(java.io.IOException failure){throw Failure.validation();}
    }
    public static void validate(LocalToolRegistration tool,ManagedRuntime runtime) {
        var profile=tool.authorizationProfile();var definition=profile.tool().definition();
        if(!profile.tool().enabled()||!profile.extractor().enabled()||!profile.tool().id().equals(tool.toolId())||!definition.id().equals(tool.toolId())
            ||!profile.tool().extractorId().equals(profile.extractor().id())||!definition.inputSchema().equals(tool.inputSchema())||!definition.outputSchema().equals(tool.outputSchema())
            ||definition.actions().stream().noneMatch(action->action.name().equals(tool.action()))||!profile.tool().packageDigest().equals(packageDigest(tool,runtime)))throw Failure.validation();
    }
}
