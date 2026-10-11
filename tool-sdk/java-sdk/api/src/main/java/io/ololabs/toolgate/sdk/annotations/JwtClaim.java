// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Repeatable;

/** A claim of the verified caller identity. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(JwtClaims.class)
public @interface JwtClaim {
    String name();
    /** A JSON pointer or a top-level claim name. */
    String claim();
    ValueType type() default ValueType.STRING;
    boolean required() default true;
}
