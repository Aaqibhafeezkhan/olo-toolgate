// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** One authoritative adapter for submission, dispatch, checkpoints and response retrieval. */
public final class RuntimeAccess {
    private final Codec codec;
    public RuntimeAccess(Codec codec) {this.codec=codec;}
    /** device is where Control placed the call (DevicePlacement), or the device a person chose; null denies. */
    public EnterpriseEvaluation evaluation(Ids.TenantId tenant,Directory directory,RequestContext c,String device,AuthorizationRequest request,String toolDigest,String packageDigest,long now) {
        if(device==null||c.bindingId()==null)throw ManagementAccess.denied();
        if(!tenant.value().equals(c.tenantId()))throw ManagementAccess.denied();
        if(c.mode()!=EnterpriseRequestMode.HUMAN) {
            var entry=c.workloadBindingId()==null?null:directory.entries().get(Kind.WORKLOAD_BINDING.id(c.workloadBindingId()));if(entry==null||!entry.enabled()||c.credentialSha256()==null)throw ManagementAccess.denied();
            var binding=codec.model(entry.document(),ControlWorkloadBinding.class);
            if(!java.security.MessageDigest.isEqual(binding.credentialSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),c.credentialSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw ManagementAccess.denied();
        }
        var toolEntry=directory.entries().get(Kind.TOOL.id(request.toolId()));if(toolEntry==null||!toolEntry.enabled())throw ManagementAccess.denied();var tool=codec.model(toolEntry.document(),ControlTool.class);
        var entry=directory.entries().get(Kind.EXTRACTOR.id(tool.extractorId()));if(entry==null||!entry.enabled())throw ManagementAccess.denied();
        var extracted=new ResourceExtraction(codec).extract(codec.model(entry.document(),ControlResourceExtractor.class),request);
        var context=new EnterpriseContext(c.requestId(),c.tenantId(),c.mode(),c.userId(),c.agentId(),c.workloadBindingId(),c.chain(),c.sessionEpoch(),c.credentialEpoch(),c.bindingId(),device);
        return new EnterpriseEvaluation(context,request.toolId(),request.action(),extracted.argumentsDigest(),extracted.resources(),toolDigest,packageDigest,now,directory.revision(),true,extracted.amount(),extracted.operation());
    }
}
