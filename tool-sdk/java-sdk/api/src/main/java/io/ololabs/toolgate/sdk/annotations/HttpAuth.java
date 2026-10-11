// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Repeatable;

/** How routes authenticate. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(HttpAuths.class)
public @interface HttpAuth {
    String id();
    AuthType type();
    String header() default "";
    String secret() default "";
    String connection() default "";
    String[] scopes() default {};
    String audience() default "";
}
