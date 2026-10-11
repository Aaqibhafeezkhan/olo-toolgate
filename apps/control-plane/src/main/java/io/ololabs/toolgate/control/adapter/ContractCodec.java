// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.ololabs.toolgate.control.application.Codec;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.control.domain.Ids.RecordId;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Offline validation against canonical schemas embedded in the shared Maven artifact. */
@ApplicationScoped
public class ContractCodec implements Codec {
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final ObjectNode canonicalDefinitions;
    private final Map<String, JsonSchema> validators = new HashMap<>();
    public ContractCodec() {
        var factory = com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BODY).build()).build();
        mapper = JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        try {
            var definitions = mapper.createObjectNode();
            var crossVersion = new java.util.ArrayList<String>();
            for (var file : io.ololabs.toolgate.contracts.ContractSet.SCHEMA_FILES) {
                var path = "/io/ololabs/toolgate/contracts/schemas/v1/" + file;
                try (var input = io.ololabs.toolgate.contracts.ContractSet.class.getResourceAsStream(path)) {
                    if (input == null) throw new IllegalStateException("Shared schema artifact is incomplete");
                    var schema = mapper.readTree(input);
                    schema.path("$defs").findValuesAsText("$ref").stream().filter(ref -> ref.startsWith("../v2/")).forEach(crossVersion::add);
                    schema.path("$defs").properties().forEach(e -> definitions.set(e.getKey(), localReferences(e.getValue().deepCopy())));
                }
            }
            adoptV2(definitions, crossVersion);
            var factorySchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            canonicalDefinitions = definitions;
            // One schema resource shares reference validators across all models. Rebuilding the
            // full graph for every wrapper exhausts the bounded production heap at startup.
            var rootNode=mapper.createObjectNode();rootNode.put("$schema","https://json-schema.org/draft/2020-12/schema");
            rootNode.put("$id","urn:olo:toolgate:canonical-contracts:v1");rootNode.set("$defs",definitions);
            var config=com.networknt.schema.SchemaValidatorsConfig.builder().cacheRefs(true).build();
            var root=factorySchema.getSchema(rootNode,config);
            for(var definition:definitions.properties()) {
                var path=new com.networknt.schema.JsonNodePath(com.networknt.schema.PathType.JSON_POINTER).append("$defs").append(definition.getKey());
                validators.put(definition.getKey(),root.getSubSchema(path));
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot initialize canonical contracts", e); }
    }
    /** v2 schema files Control already serves, validated by their v2 binding names. v1 definitions that reference v2
     *  (`../v2/...`, such as the request context) pull in those definitions too. Every adopted name must be new or
     *  identical to its v1 definition apart from descriptions, since both share one flat namespace. */
    static final java.util.List<String> V2_FILES = java.util.List.of("settings.schema.json");
    private void adoptV2(ObjectNode definitions, java.util.List<String> crossVersion) throws java.io.IOException {
        var v2 = mapper.createObjectNode();
        for (var file : java.util.List.of("identifiers.schema.json", "common.schema.json", "settings.schema.json")) {
            try (var input = io.ololabs.toolgate.contracts.ContractSet.class.getResourceAsStream("/io/ololabs/toolgate/contracts/schemas/v2/" + file)) {
                if (input == null) throw new IllegalStateException("Shared schema artifact is incomplete");
                v2.set(file, mapper.readTree(input).path("$defs"));
            }
        }
        var pending = new java.util.ArrayDeque<java.util.Map.Entry<String, String>>();
        for (var file : V2_FILES) v2.get(file).fieldNames().forEachRemaining(name -> pending.add(java.util.Map.entry(file, name)));
        for (var ref : crossVersion) { int split = ref.indexOf("#/$defs/"); pending.add(java.util.Map.entry(ref.substring("../v2/".length(), split), ref.substring(split + 8))); }
        var adopted = new java.util.HashSet<String>();
        while (!pending.isEmpty()) {
            var next = pending.poll(); var name = next.getValue();
            if (!adopted.add(name)) continue;
            var definition = v2.path(next.getKey()).path(name);
            if (definition.isMissingNode()) throw new IllegalStateException("Unsupported canonical schema reference");
            definition.findValuesAsText("$ref").forEach(ref -> {
                int split = ref.indexOf("#/$defs/");
                pending.add(java.util.Map.entry(split <= 0 ? next.getKey() : ref.substring(0, split), ref.substring(split + 8)));
            });
            var local = localReferences(definition.deepCopy());
            if (definitions.has(name) && !withoutDescriptions(definitions.get(name)).equals(withoutDescriptions(local))) throw new IllegalStateException("v2 definition " + name + " conflicts with v1");
            definitions.set(name, local);
        }
    }
    private static JsonNode withoutDescriptions(JsonNode node) {
        var copy = node.deepCopy();
        if (copy.isObject()) { ((ObjectNode) copy).remove("description"); copy.properties().forEach(e -> e.setValue(withoutDescriptions(e.getValue()))); }
        else if (copy.isArray()) for (int i = 0; i < copy.size(); i++) ((com.fasterxml.jackson.databind.node.ArrayNode) copy).set(i, withoutDescriptions(copy.get(i)));
        return copy;
    }
    private JsonNode localReferences(JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            var value = node.get("$ref").asText();
            if (!value.contains("#/$defs/")) throw new IllegalStateException("Unsupported canonical schema reference");
            ((ObjectNode) node).put("$ref", "#/$defs/" + value.substring(value.indexOf("#/$defs/") + 8));
        }
        node.elements().forEachRemaining(this::localReferences); return node;
    }
    private JsonNode parse(String document, boolean yaml) {
        if (document == null || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY) throw Failure.validation();
        try {
            JsonNode node;
            if (yaml) {
                var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
                options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(32); options.setCodePointLimit(MAX_BODY);
                node = mapper.valueToTree(new Yaml(new SafeConstructor(options)).load(document));
            } else node = mapper.readTree(document);
            if (node == null) throw Failure.validation();
            return sorted(node);
        } catch (java.io.IOException | IllegalArgumentException | org.yaml.snakeyaml.error.YAMLException e) { throw Failure.validation(); }
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var keys = new java.util.TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, sorted(node.get(key)))); return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); node.forEach(item -> result.add(sorted(item))); return result; }
        return node;
    }
    private void validate(String model, JsonNode node) {
        if (!validators.get(model).validate(node).isEmpty()) throw Failure.validation();
    }
    public Directory.Entry entry(Kind kind, String document) {
        var node = parse(document, false); validate(kind.model(), node);
        // Decode actual generated bindings as an additional structural contract boundary.
        try { mapper.treeToValue(node, Class.forName("io.ololabs.toolgate.contracts." + kind.model())); }
        catch (java.io.IOException | ClassNotFoundException | IllegalArgumentException e) { throw Failure.validation(); }
        var id = kind.id(node.get("id").asText());
        var refs = new HashSet<RecordId>();
        if (kind == Kind.TEAM) {
            add(refs, Kind.USER, node.get("userIds"));
            add(refs, Kind.ROLE, node.get("roleIds"));
        }
        if (kind == Kind.DEVICE_GROUP) add(refs,Kind.DEVICE,node.get("deviceIds"));
        if (kind == Kind.AGENT_GROUP) { add(refs,Kind.AGENT,node.get("agentIds")); add(refs,Kind.ROLE,node.get("roleIds")); }
        if (kind == Kind.TOOL_GROUP) add(refs,Kind.TOOL,node.get("toolIds"));
        if (kind == Kind.ROLE) {
            for (var rule : node.get("managementRules")) {
                var groupKind=Kind.valueOf(rule.get("groupType").asText());
                add(refs,groupKind,rule.get("groups").get("ids"));
                for(var scope:rule.get("grantableScopes")) scopeReferences(refs,scope);
            }
        }
        if (kind == Kind.AGENT || kind == Kind.DEVICE) refs.add(new Ids.UserId(node.get("ownerUserId").asText()));
        if (kind == Kind.TOOL) {
            refs.add(Kind.EXTRACTOR.id(node.get("extractorId").asText()));
            var definition = node.get("definition");
            if (!id.value().equals(definition.get("id").asText())) throw Failure.validation();
            var actions = new HashSet<String>();
            for (var action : definition.get("actions")) if (!actions.add(action.get("name").asText())) throw Failure.validation();
            // Arbitrary embedded schemas are data. Bound their depth and forbid remote references and executable metadata.
            schemaData(definition.get("inputSchema")); schemaData(definition.get("outputSchema"));
        }
        if (kind == Kind.POLICY) {
            scopeReferences(refs,node.get("scope")); add(refs,Kind.TEAM,node.get("teams").get("ids"));
            add(refs,Kind.AGENT_GROUP,node.get("agentGroups").get("ids")); add(refs,Kind.TEAM,node.get("approverTeams").get("ids"));
        }
        if(kind==Kind.GRANT) { refs.add(Kind.valueOf(node.get("sourceType").asText()).id(node.get("sourceId").asText())); scopeReferences(refs,node.get("scope")); }
        if(kind==Kind.DELEGATION) { reference(refs,Kind.TEAM,node,"teamId"); reference(refs,Kind.AGENT_GROUP,node,"agentGroupId"); scopeReferences(refs,node.get("scope")); }
        if(kind==Kind.AGENT_DELEGATION) { reference(refs,Kind.AGENT_GROUP,node,"fromAgentGroupId"); reference(refs,Kind.AGENT_GROUP,node,"toAgentGroupId"); scopeReferences(refs,node.get("scope")); }
        if(kind==Kind.BINDING) { reference(refs,Kind.TOOL_GROUP,node,"toolGroupId"); reference(refs,Kind.DEVICE_GROUP,node,"deviceGroupId"); }
        if(kind==Kind.WORKLOAD_BINDING) { reference(refs,Kind.AGENT,node,"agentId"); reference(refs,Kind.USER,node,"delegatedUserId"); reference(refs,Kind.WORKLOAD_BINDING,node,"parentBindingId"); }
        if(kind==Kind.IDENTITY_BINDING) reference(refs,Kind.USER,node,"userId");
        if(kind==Kind.DEVICE_EVIDENCE) reference(refs,Kind.DEVICE,node,"deviceId");
        return new Directory.Entry(id, node.get("enabled").asBoolean(), node.get("revision").asLong(), json(node), refs);
    }
    private void schemaData(JsonNode node) {
        if (node.isObject() && node.has("$ref") && !node.get("$ref").asText().startsWith("#")) throw Failure.validation();
        node.elements().forEachRemaining(this::schemaData);
    }
    private void add(java.util.Set<RecordId> refs, Kind kind, JsonNode ids) {
        ids.forEach(id -> refs.add(kind.id(id.asText())));
    }
    private void reference(java.util.Set<RecordId> refs,Kind kind,JsonNode node,String field) {
        if(node.has(field)) refs.add(kind.id(node.get(field).asText()));
    }
    private void scopeReferences(java.util.Set<RecordId> refs,JsonNode scope) {
        add(refs,Kind.TOOL_GROUP,scope.get("toolGroups").get("ids"));
        add(refs,Kind.DEVICE_GROUP,scope.get("deviceGroups").get("ids"));
    }
    public Directory.Entry revision(Directory.Entry entry, long revision) {
        var node = (ObjectNode) parse(entry.document(), false); node.put("revision", revision);
        return entry(entry.id().kind(), json(node));
    }
    public Import input(String document, boolean yaml, TenantId tenant) {
        var input = parse(document, yaml); validate("ControlImportRequest", input);
        var snapshot = input.get("snapshot");
        if (!snapshot.get("tenantId").asText().equals(tenant.value())) throw Failure.validation();
        var entries = new HashMap<RecordId, Directory.Entry>();
        for (var kind : Kind.values()) for (var node : snapshot.path(kind.snapshotKey())) {
            var entry = entry(kind, json(node));
            if (entries.put(entry.id(), entry) != null) throw Failure.validation();
        }
        return new Import(new Directory(snapshot.get("revision").asLong(), entries), input.get("mode").asText().equals("REPLACE"), input.get("dryRun").asBoolean());
    }
    public String snapshot(TenantId tenant, Directory directory, boolean yaml) {
        var node = mapper.createObjectNode(); node.put("formatVersion", 2); node.put("tenantId", tenant.value()); node.put("revision", directory.revision());
        for (var kind : Kind.values()) {
            var values = node.putArray(kind.snapshotKey()); directory.entries().values().stream().filter(e -> e.id().kind() == kind)
                .sorted(java.util.Comparator.comparing(e -> e.id().value())).forEach(e -> values.add(parse(e.document(), false)));
        }
        try { return yaml ? new YAMLMapper().writeValueAsString(sorted(node)) : json(node); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(sorted(mapper.valueToTree(value))); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public Object value(String document) { return parse(document, false); }
    public <T> T model(String document, Class<T> type) {
        var node = parse(document, false); validate(type.getSimpleName(), node);
        try { return mapper.treeToValue(node, type); }
        catch (java.io.IOException | IllegalArgumentException e) { throw Failure.validation(); }
    }
    public void validatePolicies(Directory directory) {
        io.ololabs.toolgate.control.application.GroupGraph.validate(directory,this);
    }
    /** Serve a self-contained OpenAPI document assembled from canonical sources, without duplicate models. */
    public String openapi() {
        try (var input = ContractCodec.class.getResourceAsStream("/META-INF/openapi.yaml")) {
            if (input == null) throw new IllegalStateException("Canonical OpenAPI is missing");
            var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            // The trusted canonical specification reuses response/parameter YAML anchors.
            // Untrusted configuration parsing above continues to allow zero collection aliases.
            options.setMaxAliasesForCollections(2048);
            var api = (ObjectNode) mapper.valueToTree(new Yaml(new SafeConstructor(options)).load(input));
            ((ObjectNode) api.get("components")).set("schemas", canonicalDefinitions.deepCopy());
            apiReferences(api); return json(api);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void apiReferences(JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            var ref = node.get("$ref").asText();
            if (ref.contains("#/$defs/")) ((ObjectNode) node).put("$ref", "#/components/schemas/" + ref.substring(ref.indexOf("#/$defs/") + 8));
        }
        node.elements().forEachRemaining(this::apiReferences);
    }
}
