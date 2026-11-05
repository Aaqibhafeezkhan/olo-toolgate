// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.OptionalLong;

/** Conservative arithmetic only; callers must separately verify reviewed facts and all current authority. */
public final class TargetDedupePolicy {
    private static final long HOUR_MS=3_600_000;
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private TargetDedupePolicy() {}
    /** Retention never comes from a default. The window is anchored to the first authorization, not a retry. */
    public static OptionalLong deadline(long firstAuthorization,long documentedRetention,
                                        long reviewExpiry,long originalDeadline) {
        if(firstAuthorization<0||firstAuthorization>MAX_SAFE_INTEGER
            ||documentedRetention<=0||documentedRetention>MAX_SAFE_INTEGER
            ||reviewExpiry<=firstAuthorization||reviewExpiry>MAX_SAFE_INTEGER
            ||originalDeadline<=firstAuthorization||originalDeadline>MAX_SAFE_INTEGER)return OptionalLong.empty();
        // Round the margin up; integer truncation must never lengthen a contractual window.
        long margin=Math.max(HOUR_MS,documentedRetention/10+(documentedRetention%10==0?0:1));
        if(margin>=documentedRetention)return OptionalLong.empty();
        long deadline;
        try{deadline=Math.addExact(firstAuthorization,documentedRetention-margin);}
        catch(ArithmeticException overflow){return OptionalLong.empty();}
        return OptionalLong.of(Math.min(deadline,Math.min(reviewExpiry,originalDeadline)));
    }
    public static boolean withinWindow(long now,long firstAuthorization,long documentedRetention,
                                       long reviewExpiry,long originalDeadline) {
        var deadline=deadline(firstAuthorization,documentedRetention,reviewExpiry,originalDeadline);
        return now>=firstAuthorization&&deadline.isPresent()&&now<deadline.getAsLong();
    }
}
