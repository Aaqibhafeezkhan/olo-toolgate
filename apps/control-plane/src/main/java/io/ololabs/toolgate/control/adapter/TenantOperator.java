// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import java.nio.file.Path;
import java.util.*;

/** Local operator entry point. No HTTP listener, schema migration or runtime credential fallback. */
public final class TenantOperator {
    private TenantOperator() {}
    public static void main(String[] args) {
        try { System.out.println(run(args)); }
        catch(Exception denied) { System.err.println("Tenant operator command rejected; consult protected configuration and tenant status"); System.exit(1); }
    }
    static String run(String... args) throws Exception {
        if(args.length<3||args.length>4)throw Failure.validation();
        var command=args[0];
        if(!Set.of("preview","provision","inspect","suspend","resume","publish-identity").contains(command))throw Failure.validation();
        if(args.length==4&&!Set.of("suspend","resume").contains(command))throw Failure.validation();
        var codec=new ContractCodec();
        var node=codec.configuration(ProtectedCustody.privateText(Path.of(args[1]),16384),false);
        if(!node.isObject()||!node.has("properties")||!node.get("properties").isObject()
            ||node.properties().stream().anyMatch(e->!Set.of("properties","developmentSqlite","developmentLoopback").contains(e.getKey())))throw Failure.validation();
        var values=new HashMap<String,String>();
        for(var field:node.get("properties").properties()){
            if(!field.getValue().isTextual())throw Failure.validation();values.put(field.getKey(),field.getValue().asText());
        }
        // Operator properties are explicit protected input; no ambient environment/system-property selection.
        var config=new io.smallrye.config.SmallRyeConfigBuilder().withDefaultValues(values).build();
        var platform=new PlatformDatabase();platform.config=config;
        if(config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false)) {
            if(!node.path("developmentSqlite").asBoolean(false)||!java.nio.file.Files.isRegularFile(Path.of(config.getValue("toolgate.quickstart.database",String.class))))throw Failure.validation();
        }
        if(!config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false)) {
            databaseTransport(config.getValue("quarkus.datasource.jdbc.url",String.class),node.path("developmentLoopback").asBoolean(false));
        }
        try {
            var vault=new VaultKeyBootstrap();vault.config=config;vault.codec=codec;
            var registry=new TenantRegistry(platform.source(),codec,command.equals("provision")?vault.required():null);
            if(command.equals("preview")||command.equals("provision")){
                var tenancy=new TenancyConfiguration();tenancy.config=config;tenancy.registry=registry;
                var inputs=new ConfigurationImportBootstrap();inputs.config=config;inputs.codec=codec;
                var coordinator=new TenancyBootstrap();coordinator.config=config;coordinator.codec=codec;
                coordinator.registry=registry;coordinator.tenancy=tenancy;coordinator.inputs=inputs;
                var result=coordinator.apply(InitializationManifest.load(Path.of(args[2]),codec),command.equals("preview"));
                if(result.stream().anyMatch(item->item.action().equals("INITIALIZATION_FAILED")))throw Failure.unavailable();
                return codec.json(result);
            }
            if(command.equals("publish-identity")){
                if(!Path.of(config.getValue("toolgate.control.identity.publication-path",String.class)).equals(Path.of(args[2])))throw Failure.validation();
                var identities=new IdentityKeyBootstrap();identities.config=config;identities.codec=codec;identities.registry=registry;identities.start(null);
                return "{\"published\":true}";
            }
            var tenant=registry.find(args[2]);if(tenant==null)throw Failure.tenantUnavailable();
            if(command.equals("suspend")||command.equals("resume")){
                if(args.length!=4||!tenant.id().value().equals(args[3]))throw Failure.validation();
                registry.suspended(tenant.id(),command.equals("suspend"));tenant=registry.find(args[2]);
            }else if(args.length!=3)throw Failure.validation();
            return codec.json(Map.of("tenantId",tenant.id().value(),"alias",tenant.alias(),"status",tenant.status(),"initialized",registry.initialized(tenant.id())));
        } finally { platform.close(); }
    }
    static void databaseTransport(String url,boolean permitDevelopment) {
        if(!url.startsWith("jdbc:postgresql://")||url.length()>4096)throw Failure.validation();
        var uri=java.net.URI.create(url.substring(5));
        if(uri.getUserInfo()!=null||uri.getRawQuery()==null||uri.getHost()==null)throw Failure.validation();
        var parameters=new HashSet<String>();
        for(var pair:uri.getRawQuery().split("&")){
            var key=pair.split("=",2)[0];
            if(!Set.of("sslmode","sslrootcert","connectTimeout","socketTimeout","ApplicationName").contains(key)||!parameters.add(key))throw Failure.validation();
        }
        var modes=Arrays.stream(uri.getRawQuery().split("&")).filter(value->value.startsWith("sslmode=")).toList();
        boolean development=permitDevelopment&&Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost());
        if(modes.size()!=1||!modes.getFirst().equals("sslmode=verify-full")&&!(development&&modes.getFirst().equals("sslmode=disable")))throw Failure.validation();
    }

}
