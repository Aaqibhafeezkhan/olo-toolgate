// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.net.URI;
import java.util.*;

/** A declaration is not authority: only the exact currently applied independent configuration review qualifies. */
public final class TargetDedupeGuarantees {
    private static final Set<String> FORBIDDEN=Set.of("authorization","proxy-authorization","cookie","set-cookie","host","content-type",
        "content-length","transfer-encoding","connection","te","upgrade","accept","user-agent","traceparent","tracestate","toolgate-service-permit","trailer","keep-alive","proxy-connection","content-encoding","expect",
        "range","content-range","if-match","if-none-match","if-range","if-modified-since","if-unmodified-since");
    private TargetDedupeGuarantees(){}
    public static void validate(TargetDedupeGuarantee guarantee){
        if(guarantee==null)return;
        try{
            var uri=URI.create(guarantee.documentationUrl());
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null
                ||FORBIDDEN.contains(guarantee.header())||guarantee.header().startsWith("x-forwarded-")
                ||guarantee.retentionMs()<=3_600_000||guarantee.retentionMs()>31_536_000_000L
                ||guarantee.scope()!=TargetDedupeScope.GLOBAL&&guarantee.credentialVersion()==null)
                throw Failure.validation();
        }catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    /** Guarantee changes always use maker/checker, including on installations configured for zero ordinary reviews. */
    static boolean independent(Codec codec,EnterpriseConfigurationCommand command){
        if(command.kind()!=ControlEntityKind.TOOL||command.document()==null)return false;
        var node=(JsonNode)codec.value(command.document());
        return node.path("definition").path("targetDedupeGuarantees").size()>0;
    }
    public static String approved(Store.Session tx,Codec codec,String toolId,String profileDigest,
                                  RouteEffectReview route,long now){
        var guarantee=route.policy().targetDedupeGuarantee();
        if(guarantee==null||guarantee.reviewExpiresAtUnixMs()<=now)return null;
        validate(guarantee);
        var entry=tx.load().entries().get(Ids.Kind.TOOL.id(toolId));if(entry==null||!entry.enabled())return null;
        var definition=codec.model(entry.document(),ControlTool.class).definition();
        if(definition.targetDedupeGuarantees()==null||definition.targetDedupeGuarantees().stream().filter(c->
            c.profileDigest().equals(profileDigest)&&c.routeId().equals(route.policy().routeId())&&c.guarantee().equals(guarantee)).count()!=1)return null;
        var all=tx.enterprise().configurations("",1001);if(all.size()>1000)return null;
        var access=new ManagementAccess(codec);
        for(var change:all){
            if(change.state()!=EnterpriseConfigurationState.APPLIED||change.command().kind()!=ControlEntityKind.TOOL
                ||!change.command().entityId().equals(toolId)||change.requiredReviews()<1
                ||change.impact().stream().noneMatch(i->i.kind()==ControlEntityKind.TOOL&&i.entityId().equals(toolId)
                    &&i.afterDigest().equals(DirectoryService.digest(entry.document()))))continue;
            var reviewers=new HashSet<String>();boolean valid=true;
            for(var review:change.reviews()){
                if(review.decision()!=EnterpriseReviewDecision.APPROVE
                    ||review.reviewerUserId().equals(change.requesterUserId())
                    ||review.reviewerUserId().equals(route.reviewedBy())||!reviewers.add(review.reviewerUserId())){valid=false;break;}
                try{
                    access.requireEntry(tx.load(),review.reviewerUserId(),"update",entry,now);
                    access.requireEntry(tx.load(),review.reviewerUserId(),"configuration_change.approve",entry,now);
                }catch(Failure denied){if(denied.status()!=403)throw denied;valid=false;break;}
            }
            if(valid&&reviewers.size()>=change.requiredReviews())return change.id();
        }
        return null;
    }
}
