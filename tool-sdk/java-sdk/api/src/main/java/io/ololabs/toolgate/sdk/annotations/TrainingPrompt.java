// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One complete training request and the call it makes, inside {@link Training#prompts()}. */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({})
public @interface TrainingPrompt {
    /** What the user says. */
    String prompt();

    /** The call's arguments, a JSON object; each key must be an input. */
    String arguments() default "{}";
}
