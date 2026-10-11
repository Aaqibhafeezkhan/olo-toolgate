// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The grouped form: inputs, globals, claims and secrets of a handler that takes only ToolContext. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ToolVariables {
    Variable[] inputs() default {};
    Global[] globals() default {};
    JwtClaim[] jwt() default {};
    Secret[] secrets() default {};
}
