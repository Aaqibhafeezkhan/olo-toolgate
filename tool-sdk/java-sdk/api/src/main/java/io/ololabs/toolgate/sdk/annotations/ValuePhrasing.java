// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** How people say one value of an input, inside {@link TrainingValues#phrasings()}. */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({})
public @interface ValuePhrasing {
    /** The value: {@code "true"} or {@code "false"} for a boolean, the enum constant for an enum, else the value as text. */
    String value();

    /** The words, such as {@code {"in Celsius", "metric"}}. */
    String[] phrasings();
}
