// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A ClientTool or ClientTrigger that uses the device's confined files area through {@code ToolContext.files()} or {@code PollContext.files()} (ADR 027). The descriptor
 *  gains DEVICE_FILES_READ, and DEVICE_FILES_WRITE with {@code write = true} (tools only: trigger access is read-only); the device enforces both. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DeviceFileAccess {
    boolean write() default false;
}
