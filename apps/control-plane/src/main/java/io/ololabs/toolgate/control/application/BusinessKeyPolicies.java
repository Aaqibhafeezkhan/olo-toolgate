// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.BusinessKeyNamespace;
import io.ololabs.toolgate.contracts.BusinessKeyPolicy;
import io.ololabs.toolgate.contracts.EnterpriseInvocationRequest;
import java.util.Objects;

/** Admission policy comes from reviewed metadata, never from an invocation's namespace fields. */
final class BusinessKeyPolicies {
    static final long DEFAULT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    static final long MAX_RETENTION_MS = 365L * 24 * 60 * 60 * 1000;

    record Binding(String namespace, String identity, String hash, String source,
                   long retentionMs, Long dedupeWindowMs) {}

    private BusinessKeyPolicies() {}

    static EnterpriseInvocationRequest identified(String tenant, EnterpriseInvocationRequest request, Binding binding) {
        if (binding == null) return request;
        // Window-derived identity needs the evaluated request and durable prior window.
        if (binding.hash() == null) return request;
        String id = "op-" + DirectoryService.digest("toolgate-operation-v1\n" + tenant + "\n"
                + request.request().toolId() + "\n" + binding.namespace() + "\n"
                + binding.identity() + "\n" + binding.hash());
        return identified(request,id,binding.hash());
    }

    static EnterpriseInvocationRequest identified(EnterpriseInvocationRequest request,String id,String hash) {
        var c=request.context();
        var context = new io.ololabs.toolgate.contracts.v2.RequestContext(id, c.tenantId(), c.mode(),
                c.credentialKind(), c.issuer(), c.userId(), c.agentId(), c.workloadBindingId(), c.chain(),
                c.sessionEpoch(), c.credentialEpoch(), c.delegatedSessionEpoch(), c.expiresAtUnixMs(),
                c.bindingId(), c.credentialSha256());
        return new EnterpriseInvocationRequest(context, request.request(), request.toolDigest(),
                request.packageDigest(), request.downstreamIdempotencyKey(), request.executionContext(), hash);
    }


    /** Resolve the current optional AGENT key declaration. */
    static BusinessKeyPolicy packaged(com.fasterxml.jackson.databind.JsonNode descriptor) {
        var key=descriptor.path("businessKey");
        if(key.isMissingNode())return null;
        if(!key.isObject()||key.has("required")&&!key.path("required").isBoolean()
                ||key.has("namespace")&&!key.path("namespace").isTextual())throw Failure.validation();
        BusinessKeyNamespace namespace;
        try { namespace=BusinessKeyNamespace.valueOf(key.path("namespace").asText("AGENT")); }
        catch(IllegalArgumentException invalid) { throw Failure.validation(); }
        String argument=key.path("argument").isMissingNode()?null:key.path("argument").isTextual()?key.path("argument").asText():null;
        if(key.has("argument")&&!key.path("argument").isTextual())throw Failure.validation();
        boolean required=key.path("required").asBoolean();
        if(!required&&namespace==BusinessKeyNamespace.AGENT&&argument==null)return null;
        return new BusinessKeyPolicy(required,namespace,argument,null,null,null);
    }

    /** Copied directory metadata cannot weaken or redirect a pinned package key declaration. */
    static BusinessKeyPolicy effective(BusinessKeyPolicy declared,BusinessKeyPolicy packaged) {
        if(packaged==null)return declared;
        if(declared==null)return packaged;
        if(packaged.namespace()!=declared.namespace()||!Objects.equals(packaged.argument(),declared.argument())
                ||packaged.required()&&!declared.required())throw Failure.conflict();
        return declared;
    }

    static BusinessKeyPolicy configured(Store.Session tx,Codec codec,String toolId) {
        var entry=tx.load().entries().get(io.ololabs.toolgate.control.domain.Ids.Kind.TOOL.id(toolId));
        if(entry==null)return null;
        var tool=codec.model(entry.document(),io.ololabs.toolgate.contracts.ControlTool.class);
        var declared=tool.definition().businessKeyPolicy();
        int slash=toolId.indexOf('/');
        if(slash<0)return declared;
        String raw=tx.packages().deployment(toolId.substring(0,slash));
        if(raw==null)return declared;
        var deployment=(com.fasterxml.jackson.databind.JsonNode)codec.value(raw);
        // Client profiles pin a managed runtime digest rather than this server deployment.
        if(!deployment.path("packageDigest").asText().equals("sha256:"+tool.packageDigest()))return declared;
        var version=tx.packages().version(deployment.path("packageDigest").asText());
        if(version==null)throw Failure.conflict();
        var document=(com.fasterxml.jackson.databind.JsonNode)codec.value(version.document());
        for(var packaged:document.path("manifest").path("tools"))
            if(packaged.path("descriptor").path("toolId").asText().equals(toolId))
                return effective(declared,packaged(packaged.path("descriptor")));
        throw Failure.conflict();
    }

    static long validatedRetention(BusinessKeyPolicy policy) {
        long retention=policy==null||policy.retentionMs()==null?DEFAULT_RETENTION_MS:policy.retentionMs();
        Long window=policy==null?null:policy.dedupeWindowMs();
        if(retention<1000||retention>MAX_RETENTION_MS
                ||window!=null&&(window<1000||window>86400000||retention<window)
                ||policy!=null&&policy.upstreamIdempotencyWindowMs()!=null
                  &&(policy.upstreamIdempotencyWindowMs()<1000||policy.upstreamIdempotencyWindowMs()>retention)
                ||policy!=null&&policy.argument()!=null&&!policy.argument().matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("Invalid business-key policy");
        return retention;
    }

    static String rawHash(String tenant,String value) {
        if(value==null||!value.matches("[A-Za-z0-9._-]{1,128}"))throw Failure.validation();
        return DirectoryService.digest("toolgate-business-key-v1\0"+tenant+"\0"+value);
    }

    static Binding bind(String tenant, EnterpriseInvocationRequest request, BusinessKeyPolicy policy) {
        var context = request.context();
        String hash = request.businessKeyHash();
        if (hash != null && !hash.matches("[a-f0-9]{64}")) throw Failure.validation();
        String source = hash == null ? null : "HEADER";
        long retention;
        try {retention=validatedRetention(policy);}
        catch(IllegalArgumentException invalid) {throw Failure.validation();}
        Long window=policy==null?null:policy.dedupeWindowMs();
        if (policy != null && policy.argument() != null) {
            String argument = policy.argument();
            if (!argument.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw Failure.validation();
            var value = request.request().arguments().get(argument);
            if (value != null) {
                if (!value.isTextual()) throw Failure.validation();
                String derived = rawHash(tenant,value.textValue());
                if (hash != null && !Objects.equals(hash, derived)) throw Failure.conflict();
                hash = derived;
                source = "ARGUMENT";
            }
        }
        if (hash == null && policy != null && policy.required()) throw Failure.validation();
        if (hash == null && window == null) return null;
        BusinessKeyNamespace namespace = policy == null
                ? context.agentId() == null ? BusinessKeyNamespace.DELEGATED_USER : BusinessKeyNamespace.AGENT
                : policy.namespace();
        String identity = switch (namespace) {
            case AGENT -> context.agentId();
            case DELEGATED_USER -> context.mode() == io.ololabs.toolgate.contracts.v2.RequestMode.SERVICE
                    ? null : context.userId();
            case TENANT -> "";
        };
        if (identity == null) throw ManagementAccess.denied();
        return new Binding(namespace.name(), identity, hash, hash == null ? "DEDUPE_WINDOW" : source,
                           retention, window);
    }
}
