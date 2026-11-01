// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Training values for one input: realistic example values, and how people say particular values. Put it on the handler
 * parameter beside its {@link Param}, or in {@link Training#parameters()} with a {@link #name()}.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface TrainingValues {
    /** The input name; only inside {@link Training#parameters()}, where it is required. */
    String name() default "";

    /**
     * Example values. For a string input each is the value as written; for any other type each is JSON, such as
     * {@code "42"}, {@code "true"} or {@code "[\"a\", \"b\"]"}.
     */
    String[] examples() default {};

    /** Words people use for particular values, such as {@code "largest first"} for {@code true}. */
    ValuePhrasing[] phrasings() default {};
}
