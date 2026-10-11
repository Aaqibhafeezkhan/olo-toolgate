// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Repeatable;

/** A named HTTP route, the only way tool code reaches HTTP. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(HttpRoutes.class)
public @interface HttpRoute {
    String id();
    String destination();
    HttpMethod method();
    /** RFC 6570 level-1 template; variables carry a pattern, {name:regex}. */
    String path();
    String[] query() default {};
    String[] headers() default {};
    String[] requestContentTypes() default "application/json";
    long maxRequestBytes() default 1_048_576;
    long maxResponseBytes() default 4_194_304;
    Streaming streaming() default Streaming.NONE;
    int timeoutMs() default 10_000;
    String auth() default "";
}
