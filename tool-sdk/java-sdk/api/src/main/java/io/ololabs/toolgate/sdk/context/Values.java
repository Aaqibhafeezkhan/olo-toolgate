// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Typed read access to declared values. Reading an undeclared name, or with the wrong type, fails. */
public interface Values {
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
}
