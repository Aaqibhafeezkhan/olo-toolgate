// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** Value type (authoring README §7); INFER takes the type from the Java type. */
public enum ValueType { INFER, STRING, INT, LONG, NUMBER, BOOLEAN, DATE, DATE_TIME, DURATION, ENUM, URI, OBJECT, ARRAY, BINARY }
