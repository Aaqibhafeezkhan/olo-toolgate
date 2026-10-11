// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Repeatable;

/** A self-test; at most 8 per tool. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@Repeatable(Examples.class)
public @interface Example {
    /** JSON object. */
    String arguments();
    String expectedOutput() default "";
    String expectedError() default "";
    /**
     * A class path resource holding the recorded exchanges this example's route calls get, in the format of
     * spec/authoring/testing.md. An example without one must not call a route.
     */
    String exchanges() default "";
}
