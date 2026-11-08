// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.trigger;

import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.context.ToolLogger;
import java.util.Map;
import java.util.Optional;

/**
 * What a POLL trigger's {@code check()} can reach besides its config. The same under every executor and in
 * {@code TriggerHarness}. Route calls, TCP destinations and secrets for server triggers are not part of this SDK
 * version: the processor refuses triggers that declare them.
 */
public interface PollContext {
    /** {@code <package id>/<trigger name>}. */
    String triggerId();

    /** The Control-assigned instance this process runs. */
    String instanceId();

    /** The lease's fencing number. */
    long leaseEpoch();

    long configRevision();

    /** The package's globals as the assignment resolved them: strings, booleans, longs, doubles, lists and maps. */
    Map<String, Object> globals();

    /** The checkpoint the last committed (or in-process, last returned) check left; empty on the first run. */
    Optional<Checkpoint> checkpoint();

    /** When this process started, as an RFC 3339 UTC timestamp. */
    String startedAt();

    /**
     * The device files area, read-only, for a {@code @ClientTrigger} declared with {@code @DeviceFileAccess}.
     * Anything else gets {@code PlatformError FILES_REFUSED}.
     */
    DeviceFiles files();

    ToolLogger log();

    /** True once the executor asked the process to stop; a long check should return early. */
    boolean stopping();
}
