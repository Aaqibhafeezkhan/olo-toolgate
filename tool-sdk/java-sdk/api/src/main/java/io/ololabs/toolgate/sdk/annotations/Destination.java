// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Repeatable;

/** An HTTP destination; the host is a reviewed service-profile setting. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(Destinations.class)
public @interface Destination {
    String id();
    String hostSetting();
    /** Proposed value only; never part of the descriptor. */
    String defaultHost() default "";
    int port() default 443;
    String[] spkiPins() default {};
    String caBundle() default "";
}
