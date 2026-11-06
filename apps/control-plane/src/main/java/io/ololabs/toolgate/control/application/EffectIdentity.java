// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids;
import java.util.List;

/** Current logical identity: explicit keys, one hash domain, no historical/default aliases. */
public final class EffectIdentity {
    private EffectIdentity() {}
    private static String scope(String value) {
        try{return Ids.valid(value);}catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    public static String key(String value) {
        if(value==null||!value.matches("[A-Za-z0-9._:-]{1,128}"))throw Failure.validation();
        return value;
    }
    public static String of(Codec codec,String tenant,String operation,String route,String key) {
        return "eff-"+DirectoryService.digest(codec.json(List.of("toolgate-effect-v1",
            scope(tenant),scope(operation),scope(route),key(key))));
    }
}
