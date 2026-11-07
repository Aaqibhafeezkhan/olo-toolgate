// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a trigger that runs on a Tool Host, and on a device too when it uses no route, TCP destination or secret
 * (plan 5.7). In POLL mode the annotated method is {@code public PollResult<E> check(C config, PollContext ctx)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServerTrigger {
    /** Trigger local name; the triggerId is {@code <package id>/<name>}. Shares the export namespace with tools. */
    String name();
    TriggerMode mode();
    /** Shown to administrators, 1-1024 characters. */
    String description();
    /** The assignment config, a record. */
    Class<?> config();
    /** The event data, a record. */
    Class<?> event();
    /** Reverse-DNS event type ending with a major version, such as {@code examples.files.observed.v1}. */
    String eventType();
    SourceReplay sourceReplay();
    /** POLL interval, 5000..86400000 ms. Required in POLL mode. */
    long intervalMs() default -1;
    /** PUSH resync interval, 0 or 60000..86400000 ms. PUSH mode only. */
    long resyncIntervalMs() default -1;
    /** 1..3600. */
    int maxEventsPerHour();
    /** Serialized event data, 1..16384 bytes. */
    int maxEventBytes() default 16_384;
    /** Encoded checkpoint, 1..16384 bytes. */
    int maxCheckpointBytes() default 16_384;
    /** 32..4096. */
    int memoryMiB() default 256;
    /** 0..6000. */
    int maxBrokerCallsPerMinute() default 120;
    /** 0..30000. */
    int stopGraceMs() default 10_000;
    OverflowPolicy overflow() default OverflowPolicy.BLOCK;
    /** Marks the event type as tolerating loss; required for DROP_* overflow. */
    boolean lossTolerant() default false;
    TerminalRejectionPolicy onTerminalRejection() default TerminalRejectionPolicy.HALT;
    /** Bump when the checkpoint format changes; the executor then starts without a checkpoint. 1..65535. */
    int checkpointSchemaVersion() default 1;
}
