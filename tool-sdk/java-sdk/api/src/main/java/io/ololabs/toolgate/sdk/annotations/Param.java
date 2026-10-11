// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An AI-supplied input on a handler parameter, or a property on a record component. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Param {
    /** Defaults to the source spelling of the parameter or component. */
    String name() default "";
    /** Required for inputs; empty omits it on output properties. */
    String description();
    ValueType type() default ValueType.INFER;
    boolean required() default true;
    /** Parsed as the type; a default makes the input optional. */
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
