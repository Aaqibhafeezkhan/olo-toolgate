// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import javax.lang.model.element.Element;

/** A declaration error, reported at its element as a compile error. */
final class Problem extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Element element;

    Problem(Element element, String message) {
        super(message, null, false, false);
        this.element = element;
    }
}
