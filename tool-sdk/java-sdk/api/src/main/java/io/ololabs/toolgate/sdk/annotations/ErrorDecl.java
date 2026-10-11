// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A declared business error code. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ErrorDecl {
    String code();
    String description();
    boolean retryable() default false;
    /** Optional JSON Schema for the error data. */
    String dataSchema() default "";
}
