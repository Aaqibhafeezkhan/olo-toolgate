// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Everything a handler can reach besides its typed parameters. Identical in standalone and governed mode. */
public interface ToolContext {
    String getStringValue(String name);
    int getIntValue(String name);
    long getLongValue(String name);
    double getNumberValue(String name);
    boolean getBooleanValue(String name);
    LocalDate getDateValue(String name);
    OffsetDateTime getDateTimeValue(String name);
    Duration getDurationValue(String name);
    <T> T getObjectValue(String name, Class<T> type);
    <T> List<T> getListValue(String name, Class<T> type);
    byte[] getBinaryValue(String name);

    Values global();
    Values secret();
    Jwt jwt();

    HttpClient http();
    TcpConnector tcp();
    Invocation invocation();

    /** Fraction 0..1, monotonic; ignored for sync calls without a progress token. */
    void progress(double fraction, String message);
    boolean isCancelled();
    CancellationToken cancellation();

    <T> T requestInput(InputRequest<T> request);
    ToolLogger log();
}
