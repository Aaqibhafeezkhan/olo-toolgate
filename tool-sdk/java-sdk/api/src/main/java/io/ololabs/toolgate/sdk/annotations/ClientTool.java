// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a tool that runs on an endpoint device. Client tools have no network access or secrets. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ClientTool {
    /** Tool local name; the toolId is derived from the package id. */
    String name();
    /** Human title, 1-100 characters. Exactly one of title and text is set. */
    String title() default "";
    /** Alias of title. */
    String text() default "";
    /** Shown to the model, 1-2048 characters. */
    String description();
    /** Guidance appended to the description, at most 512 characters. */
    String useWhen() default "";
    EffectSafety effect() default EffectSafety.UNKNOWN;
    boolean destructive() default false;
    OpenWorld openWorld() default OpenWorld.DEFAULT;
    /** Runs as a task. */
    boolean async() default false;
    /** -1 means 30000 for sync tools and 900000 for async tools. */
    long maxDurationMs() default -1;
    boolean stateless() default false;
    /** -1 means unlimited. */
    int maxConcurrency() default -1;
    BusinessKey requiresBusinessKey() default BusinessKey.DEFAULT;
    KeyNamespace businessKeyNamespace() default KeyNamespace.AGENT;
    /** Input carrying the business key when it is not sent as Idempotency-Key. */
    String businessKeyArgument() default "";
    /** Tasks only. */
    boolean cancelIfAbandoned() default false;
    ErrorDecl[] errors() default {};
    String[] toolsets() default {};
    RuntimeTier[] tiers() default RuntimeTier.OCI_SANDBOX;
}
