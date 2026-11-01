// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Training hints for a tool: how people ask for it, and how they do not. The processor writes them to
 * {@code META-INF/toolgate/training/annotations.json}; the package build moves that file out of the jar to
 * {@code training/annotations.json} beside the {@code .tgpkg} and into its {@code .tool-schema.json}. With source
 * retention the hints are not in the class files either, so editing one changes no digest. They sit next to the tool's
 * name, description and inputs and are checked against them on every build: a placeholder such as {@code {city}} must
 * name an input, and an example value must suit its input's type (tool-sdk/spec/authoring/training.md).
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Training {
    /** User requests that call the tool; {@code {input}} placeholders are filled with example values. */
    String[] phrasings() default {};

    /** Requests that call the tool with no arguments, or only its required ones, leaving the rest at their defaults. */
    String[] defaultPhrasings() default {};

    /** Look-alike requests that must not call the tool, such as general questions about the same topic. */
    String[] negatives() default {};

    /**
     * Example values per input, for inputs the handler does not take as a parameter (grouped {@link ToolVariables}
     * inputs) or when they read better here. Each needs {@link TrainingValues#name()}.
     */
    TrainingValues[] parameters() default {};

    /** Complete request and call pairs, for a request whose arguments a placeholder cannot express. */
    TrainingPrompt[] prompts() default {};
}
