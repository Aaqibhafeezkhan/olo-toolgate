// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An input in the grouped ToolVariables form. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface Variable {
    String name();
    String description();
    ValueType type();
    boolean required() default true;
    String defaultValue() default "";
    /** Minimum, parsed as the value type ({@code INT}, {@code LONG}, {@code NUMBER}). */
    String min() default "";
    /** Maximum, parsed as the value type. */
    String max() default "";
    /** Minimum string length; -1 for none. */
    int minLength() default -1;
    /** Maximum string length; -1 for none. */
    int maxLength() default -1;
    /** ECMA-262 pattern. */
    String pattern() default "";
    /** Allowed values, in declaration order; makes a string an {@code ENUM}. */
    String[] enumValues() default {};
    /** JSON Schema format, for {@code STRING} values only. */
    String format() default "";
    /** Redacted from logs, errors and audit. */
    boolean sensitive() default false;
}
