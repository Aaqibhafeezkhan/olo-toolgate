// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.util.List;

/** The device's confined files area (ADR 027): generic primitives the client brokers for a ClientTool declared with
 *  {@code @DeviceFileAccess}. Paths are relative and portable; the device confines them, and a path operation may name
 *  only paths the invocation was authorized with. Text is UTF-8. Refusals raise {@code PlatformError FILES_REFUSED}. */
public interface DeviceFiles {
    record Info(String path, long bytes) {}
    /** Every file and directory, sorted. */
    List<String> list();
    String readText(String path);
    Info info(String path);
    void writeText(String path, String text);
    void appendText(String path, String text);
    void mkdir(String path);
    /** Moves a file; the destination must not exist. */
    void rename(String path, String destination);
}
