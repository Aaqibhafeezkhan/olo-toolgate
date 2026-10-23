// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The tool's single permission and the resources each call acts on. Without it a tool is one {@code invoke} action on
 * {@code tool://<toolId>}. With it, administrators grant exactly {@link #action()} (for example {@code memory.read}) on
 * resources built per call from {@link #resources()} templates such as {@code olo://{tenant}/conversation/{conversationId}}:
 * ToolGate fills {@code {tenant}} with the verified tenant and each other placeholder with the required string argument of
 * that name, which must be an opaque id (one path segment).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Authorization {
    /** Lower-case dotted permission, for example {@code rag.query}; no wildcards. */
    String action();

    /** One to eight locator templates, each starting {@code <scheme>://{tenant}/}. */
    String[] resources();
}
