// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A self-test of a POLL trigger, run by {@code toolgate check} and {@code TriggerHarness.runExamples()}: one check with
 * this config, starting checkpoint and device files, which must emit exactly the expected events and checkpoint. Its
 * name is listed in the descriptor's {@code selfTests}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(TriggerExamples.class)
public @interface TriggerExample {
    /** Lower-case name, unique per trigger. */
    String name();
    /** The config, a JSON object. */
    String config();
    /** The starting checkpoint, a JSON object; none when empty. */
    String checkpoint() default "";
    /** The device files area, a JSON object of path to text. */
    String files() default "";
    /** A JSON array of {@code {id?, subject?, data}}, in emit order. */
    String expectedEvents();
    /** The checkpoint the check must return, a JSON object; when empty, it must return none. */
    String expectedCheckpoint() default "";
}
