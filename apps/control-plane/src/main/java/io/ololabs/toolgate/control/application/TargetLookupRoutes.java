// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;
import io.ololabs.toolgate.contracts.v2.*;
import java.util.Objects;

/** Narrow current adapter shape. Unsupported routes never silently select another transport. */
public final class TargetLookupRoutes {
    public static final String KEY_SLOT="{effectKey:tg-[a-f0-9]+}";
    private TargetLookupRoutes(){}
    public static RouteDeclaration status(ServiceProfile profile,String routeId){
        var routes=profile.routes().stream().filter(r->r.id().equals(routeId)).toList();
        var reviews=profile.effectReviews()==null?java.util.List.<RouteEffectReview>of():profile.effectReviews().stream()
            .filter(r->r.policy().routeId().equals(routeId)).toList();
        if(routes.size()!=1||reviews.size()!=1||reviews.getFirst().policy().classification()!=EffectClassification.READ
            ||reviews.getFirst().policy().consentRequired()||reviews.getFirst().policy().targetDedupeGuarantee()!=null)
            throw Failure.validation();
        var route=routes.getFirst();
        if(route.method()!=HttpMethod.GET||route.streaming()!=Streaming.NONE||!route.query().isEmpty()
            ||!route.headers().isEmpty()||route.maxResponseBytes()<1||route.maxResponseBytes()>65536
            ||route.path().indexOf(KEY_SLOT)<0||route.path().indexOf(KEY_SLOT)!=route.path().lastIndexOf(KEY_SLOT)
            ||!route.path().replace(KEY_SLOT,"EFFECT_KEY").matches("/[A-Za-z0-9/_-]{1,2047}"))
            throw Failure.validation();
        return route;
    }
    public static RouteDeclaration require(ServiceProfile profile,String writeRoute,String statusRoute){
        var writes=profile.routes().stream().filter(r->r.id().equals(writeRoute)).toList();
        var status=status(profile,statusRoute);
        if(writes.size()!=1||writeRoute.equals(statusRoute)||!writes.getFirst().destination().equals(status.destination())
            ||!Objects.equals(writes.getFirst().auth(),status.auth()))throw Failure.validation();
        return status;
    }
    public static void validate(ServiceProfile profile){
        if(profile.effectReviews()==null)return;
        for(var review:profile.effectReviews()){
            var guarantee=review.policy().targetDedupeGuarantee();
            if(guarantee!=null&&guarantee.statusRouteId()!=null)require(profile,review.policy().routeId(),guarantee.statusRouteId());
        }
    }
}
