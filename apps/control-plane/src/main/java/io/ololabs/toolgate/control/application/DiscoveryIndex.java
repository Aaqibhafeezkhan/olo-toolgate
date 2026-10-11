// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Read-only metadata probes. Visibility never substitutes for authorizing real extracted arguments. */
public final class DiscoveryIndex {
    private final Codec codec;
    public DiscoveryIndex(Codec codec) {this.codec=codec;}
    public record Eligibility(String toolId,String action,ResourceDescriptor resource,Decision decision) {}
    public List<Eligibility> rules(Ids.TenantId tenant,Directory directory,RequestContext c,String device,long now) {
        return rules(tenant,directory,c,device,now,budget->0L);
    }
    public List<Eligibility> rules(Ids.TenantId tenant,Directory directory,RequestContext c,String device,long now,java.util.function.Function<String,Long> quotas) {
        var result=new TreeMap<String,Eligibility>();var evaluator=new EnterpriseEvaluator(codec);
        var context=new EnterpriseContext(c.requestId(),c.tenantId(),c.mode(),c.userId(),c.agentId(),c.workloadBindingId(),c.chain(),c.sessionEpoch(),c.credentialEpoch(),c.bindingId(),device);
        var resources=new TreeMap<String,ResourceDescriptor>();
        for(var entry:directory.entries().values())if(entry.id().kind()==Kind.GRANT&&entry.enabled())for(var r:codec.model(entry.document(),ControlAccessGrant.class).scope().resources()) {
            var resource=new ResourceDescriptor(r.kind(),r.match()==EnterpriseResourceMatch.ANY?(r.kind()==ResourceKind.URL?"https://discovery.invalid/":"discovery-probe"):r.locator());resources.put(codec.json(resource),resource);
        }
        for(var entry:directory.entries().values())if(entry.id().kind()==Kind.TOOL&&entry.enabled()) {
            var tool=codec.model(entry.document(),ControlTool.class);
            var extractorEntry=directory.entries().get(Kind.EXTRACTOR.id(tool.extractorId()));if(extractorEntry==null||!extractorEntry.enabled())continue;var extractor=codec.model(extractorEntry.document(),ControlResourceExtractor.class);
            for(var action:tool.definition().actions())for(var resource:resources.values())if(action.resourceKinds().contains(resource.kind())) {
                var affected=new ArrayList<>(extractor.fixedResources());if(affected.isEmpty()||!extractor.fields().isEmpty())affected.add(resource);affected=new ArrayList<>(new LinkedHashSet<>(affected));
                var input=new EnterpriseEvaluation(context,tool.id(),action.name(),"0".repeat(64),affected,evaluator.toolDigest(directory,tool),tool.packageDigest(),now,directory.revision(),true,0L,"discovery");
                var decision=evaluator.evaluateWithQuotas(tenant,directory,input,quotas);
                if(decision.decision()!=Decision.BLOCK) {var rule=new Eligibility(tool.id(),action.name(),resource,decision.decision());result.put(codec.json(rule),rule);}
            }
        }
        return List.copyOf(result.values());
    }
}
