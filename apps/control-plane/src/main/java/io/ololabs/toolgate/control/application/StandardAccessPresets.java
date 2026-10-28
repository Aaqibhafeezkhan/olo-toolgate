// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ControlExecutionBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Empty standard groups carry explicit role/grant presets; installation never enrolls actors into them. */
public final class StandardAccessPresets {
    private static final JsonNode CATALOG=load();
    private StandardAccessPresets() {}
    public static Map<String,String> provenance(Codec codec) {
        return Map.of("source","release-initial-configuration:"+CATALOG.path("bundleId").asText(),"digest",DirectoryService.digest(codec.json(CATALOG)));
    }
    private static JsonNode load() {
        try(var input=StandardAccessPresets.class.getResourceAsStream("/initial-configuration/manifest.json")) {
            if(input==null)throw new IllegalStateException("Access presets missing");
            var mapper=new ObjectMapper();var catalog=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(input);
            var records=catalog.putObject("records");
            for(var name:catalog.path("files")) {
                if(!Set.of("groups.json","roles.json","grants.json","policies.json","bindings.json").contains(name.asText()))throw new IllegalStateException("Invalid configuration file");
                try(var file=StandardAccessPresets.class.getResourceAsStream("/initial-configuration/"+name.asText())) {
                    if(file==null)throw new IllegalStateException("Initial configuration file missing");
                    var values=mapper.readTree(file);values.properties().forEach(value->{if(records.has(value.getKey()))throw new IllegalStateException("Duplicate initial collection");records.set(value.getKey(),value.getValue());});
                }
            }
            return catalog;
        }catch(java.io.IOException failure){throw new IllegalStateException("Access presets invalid",failure);}
    }
    public static void install(Map<Ids.RecordId,Directory.Entry> entries,Codec codec) {
        Map<String,Ids.Kind> kinds=Map.of("teams",Ids.Kind.TEAM,"agentGroups",Ids.Kind.AGENT_GROUP,
            "deviceGroups",Ids.Kind.DEVICE_GROUP,"toolGroups",Ids.Kind.TOOL_GROUP,"roles",Ids.Kind.ROLE,
            "grants",Ids.Kind.GRANT,"policies",Ids.Kind.POLICY,"bindings",Ids.Kind.BINDING);
        for(var kind:kinds.entrySet())for(var document:CATALOG.path("records").path(kind.getKey())) {
            var entry=codec.entry(kind.getValue(),codec.json(document));
            if(GroupGraph.group(entry.id().kind())&&!GroupGraph.members(entry,codec).isEmpty())throw new IllegalStateException("Initial access presets must have empty memberships");
            entries.putIfAbsent(entry.id(),entry);
        }
    }
    /** Both resource-group classifications constrain the selected execution binding. Unknown actions require Admin. */
    public static void validateBinding(ControlExecutionBinding binding) {
        for(var group:List.of(binding.toolGroupId(),binding.deviceGroupId())) {
            String actions=group.equals("ReadOnlyToolGroup")||group.equals("ReadOnlyDeviceGroup")?"readActions":
                group.equals("ReadAndWriteToolGroup")||group.equals("ReadAndWriteDeviceGroup")?"writeActions":null;
            if(actions==null)continue;
            var allowed=new HashSet<String>();CATALOG.path(actions).forEach(a->allowed.add(a.asText()));
            if(!allowed.containsAll(binding.actions()))throw new IllegalArgumentException("Execution binding exceeds standard group permission level");
        }
    }
}
