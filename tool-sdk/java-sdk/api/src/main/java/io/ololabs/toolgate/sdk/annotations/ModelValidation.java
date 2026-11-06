// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Prompts that test a model trained for the tool, kept apart from its {@link Training} hints: the dataset builder never
 * trains on them, so they measure how the trained model copes with requests it has not seen. The processor writes them
 * to the tool's {@code modelValidation} entry of {@code training/annotations.json}, next to the training hints, and
 * checks them on every build: a prompt's arguments must be a valid call of the tool (every required input, values of
 * the right type), and no validation prompt may also be a training phrasing or prompt
 * (tool-sdk/spec/authoring/training.md).
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface ModelValidation {
    /** Requests that must call the tool, each with the exact arguments the call should carry. */
    TrainingPrompt[] prompts() default {};

    /** Requests that must not call the tool (answered in plain text), such as general questions on the same topic. */
    String[] negatives() default {};
}
