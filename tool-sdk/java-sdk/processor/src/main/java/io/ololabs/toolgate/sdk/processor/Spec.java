// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.annotations.Variable;
import java.util.List;

/** The attributes Param, Variable and Global share. */
record Spec(String name, String description, ValueType type, boolean required, String defaultValue, String min,
        String max, int minLength, int maxLength, String pattern, List<String> enumValues, String format,
        boolean sensitive) {

    static Spec of(Param p) {
        return new Spec(p.name(), p.description(), p.type(), p.required(), p.defaultValue(), p.min(), p.max(),
                p.minLength(), p.maxLength(), p.pattern(), List.of(p.enumValues()), p.format(), p.sensitive());
    }

    static Spec of(Variable v) {
        return new Spec(v.name(), v.description(), v.type(), v.required(), v.defaultValue(), v.min(), v.max(),
                v.minLength(), v.maxLength(), v.pattern(), List.of(v.enumValues()), v.format(), v.sensitive());
    }

    static Spec of(Global g) {
        return new Spec(g.name(), g.description(), g.type(), g.required(), g.defaultValue(), g.min(), g.max(),
                g.minLength(), g.maxLength(), g.pattern(), List.of(g.enumValues()), g.format(), g.sensitive());
    }
}
