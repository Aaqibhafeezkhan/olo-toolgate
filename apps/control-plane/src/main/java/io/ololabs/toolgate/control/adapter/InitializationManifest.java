// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.control.application.Failure;
import java.nio.file.*;
import java.util.*;

/** Bounded local-only manifest and includes. Every file is read before any new tenant is allocated. */
public record InitializationManifest(String defaultAlias,List<Spec> tenants) {
    /** Counts actual bytes read, including repeated references, before any tenant allocation. */
    static final class Budget {
        private int remaining=16*1024*1024;
        String read(Path path,int limit) throws java.io.IOException {
            var bytes=ProtectedCustody.read(path,Math.min(limit,remaining));
            remaining-=bytes.length;
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public record Endpoints(String serverId,String controlUrl,String gatewayUrl) {}
    public record Spec(String alias,String name,List<String> hosts,String packet,String trust,List<TenantInitialization.Layer> layers,Endpoints endpoints) {}
    public static InitializationManifest load(Path path,ContractCodec codec) throws java.io.IOException {
        var budget=new Budget();var root=path.toAbsolutePath().getParent().toRealPath();var node=codec.configuration(budget.read(path.toAbsolutePath(),2097152),path.toString().endsWith(".yaml")||path.toString().endsWith(".yml"));
        exact(node,Set.of("formatVersion","defaultTenantAlias","commonImports","tenants"));
        if(!node.path("formatVersion").isIntegralNumber()||node.path("formatVersion").asLong()!=1||!node.path("defaultTenantAlias").isTextual()||!node.path("tenants").isArray()||node.path("tenants").isEmpty()||node.path("tenants").size()>256)throw Failure.validation();
        String defaultAlias=TenantRegistry.alias(node.path("defaultTenantAlias").asText());
        var common=imports(root,node.path("commonImports"),true,codec,budget);var aliases=new HashSet<String>();var hosts=new HashSet<String>();var specs=new ArrayList<Spec>();
        for(var tenant:node.path("tenants")){
            exact(tenant,Set.of("alias","name","hosts","administratorProvisioningRef","imports","endpoints"));
            if(!tenant.path("alias").isTextual()||!tenant.path("name").isTextual()||!tenant.path("administratorProvisioningRef").isTextual())throw Failure.validation();
            var alias=TenantRegistry.alias(tenant.path("alias").asText());if(!aliases.add(alias))throw Failure.validation();
            String name=tenant.path("name").asText();if(name.isBlank()||name.length()>256)throw Failure.validation();
            if(!tenant.path("hosts").isArray()||tenant.path("hosts").isEmpty()||tenant.path("hosts").size()>32)throw Failure.validation();
            var owned=new ArrayList<String>();for(var host:tenant.path("hosts")){if(!host.isTextual())throw Failure.validation();var normalized=TenantHosts.normalize(host.asText());if(!hosts.add(normalized))throw Failure.validation();owned.add(normalized);}
            var layers=new ArrayList<>(common);layers.addAll(imports(root,tenant.path("imports"),false,codec,budget));
            var directory=inside(root,tenant.path("administratorProvisioningRef").asText());
            var packet=budget.read(inside(root,root.relativize(directory.resolve("packet.json")).toString()),2097152);
            var trust=budget.read(inside(root,root.relativize(directory.resolve("trust.json")).toString()),16384);
            Endpoints endpoints=null;
            if(tenant.has("endpoints")) {
                var value=tenant.get("endpoints");exact(value,Set.of("serverId","controlUrl","gatewayUrl"));
                if(!value.path("controlUrl").isTextual()||!value.path("gatewayUrl").isTextual()||value.has("serverId")&&!value.path("serverId").isTextual())throw Failure.validation();
                endpoints=new Endpoints(value.has("serverId")?value.path("serverId").asText():null,value.path("controlUrl").asText(),value.path("gatewayUrl").asText());
                // Validate URL shape and ownership before registration; a missing server ID defaults to the UUID later.
                var profile=new io.ololabs.toolgate.control.application.Store.RuntimeProfile(endpoints.serverId()==null?"pending":endpoints.serverId(),name,endpoints.controlUrl(),endpoints.gatewayUrl());
                for(var origin:List.of(profile.controlUrl(),profile.gatewayUrl()))if(!owned.contains(TenantRequestResolver.authority(List.of(java.net.URI.create(origin).getRawAuthority()),443).host()))throw Failure.validation();
            }
            specs.add(new Spec(alias,name,List.copyOf(owned),packet,trust,List.copyOf(layers),endpoints));
        }
        if(!aliases.contains(defaultAlias))throw Failure.validation();return new InitializationManifest(defaultAlias,List.copyOf(specs));
    }
    private static List<TenantInitialization.Layer> imports(Path root,JsonNode paths,boolean common,ContractCodec codec,Budget budget) throws java.io.IOException {
        if(paths.isMissingNode())return List.of();if(!paths.isArray()||paths.size()>64)throw Failure.validation();
        var layers=new ArrayList<TenantInitialization.Layer>();
        for(var item:paths){if(!item.isTextual())throw Failure.validation();var file=inside(root,item.asText());layers.add(new TenantInitialization.Layer(item.asText(),codec.configuration(budget.read(file,2097152),item.asText().endsWith(".yaml")||item.asText().endsWith(".yml")),common));}
        return List.copyOf(layers);
    }
    private static Path inside(Path root,String relative) throws java.io.IOException {
        if(relative==null||relative.isBlank()||relative.length()>1024||Path.of(relative).isAbsolute())throw Failure.validation();
        var path=root.resolve(relative).normalize();if(!path.startsWith(root))throw Failure.validation();
        var real=path.toRealPath();if(!real.startsWith(root))throw Failure.validation();return real;
    }
    private static void exact(JsonNode node,Set<String> fields){if(!node.isObject())throw Failure.validation();for(var field:node.properties())if(!fields.contains(field.getKey()))throw Failure.validation();}
}
