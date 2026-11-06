// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** A package declaration cannot stamp its own target identity or retain a disabled reviewer's authority. */
final class TargetFencing {
    private TargetFencing(){}
    static void currentReview(Store.Session tx,Codec codec,ServiceProfile profile,String route,FencedTarget target,long now){
        var reviews=profile.effectReviews()==null?List.<RouteEffectReview>of():profile.effectReviews().stream()
            .filter(r->r.policy().routeId().equals(route)).toList();
        if(reviews.size()!=1||!target.equals(reviews.getFirst().policy().fencedTarget()))throw ManagementAccess.denied();
        var review=reviews.getFirst();
        if(review.reviewedAtUnixMs()>now||!tx.load().entries().containsKey(Ids.Kind.USER.id(review.reviewedBy())))
            throw ManagementAccess.denied();
        new ManagementAccess(codec).requireAll(tx.load(),review.reviewedBy(),"package.deploy",now);
    }
}
