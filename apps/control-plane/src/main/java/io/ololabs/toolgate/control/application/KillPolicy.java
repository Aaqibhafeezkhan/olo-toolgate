// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** Scope matching uses Control-owned immutable facts, never fields submitted by an executor. */
public final class KillPolicy {
    private KillPolicy(){}
    /** Null means unknown (fail closed); empty means known not applicable. */
    public record Subject(String packageId,String packageDigest,String publisher,String toolId,String poolId,String imageDigest){}
    public static void validate(KillScope scope){
        if(scope==null||scope.kind()==null)throw Failure.validation();
        if(scope.kind()==KillScopeKind.TENANT){if(scope.value()!=null)throw Failure.validation();return;}
        if(scope.value()==null)throw Failure.validation();
        if(scope.kind()==KillScopeKind.PACKAGE_DIGEST||scope.kind()==KillScopeKind.RUNTIME_IMAGE){
            if(!scope.value().matches("sha256:[a-f0-9]{64}"))throw Failure.validation();
        }else try{Ids.valid(scope.value());}catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    public static boolean matches(KillScope scope,Subject subject){
        validate(scope);
        String actual=switch(scope.kind()){
            case TENANT -> scope.value();
            case PACKAGE -> subject.packageId();
            case PACKAGE_DIGEST -> subject.packageDigest();
            case PUBLISHER -> subject.publisher();
            case TOOL -> subject.toolId();
            case POOL -> subject.poolId();
            case RUNTIME_IMAGE -> subject.imageDigest();
        };
        return scope.kind()==KillScopeKind.TENANT||actual==null||actual.equals(scope.value());
    }
    public static boolean blocks(KillMode mode,KillScope scope,Subject subject,boolean running){
        if(mode==null)throw Failure.validation();
        return (!running||mode==KillMode.TERMINATE)&&matches(scope,subject);
    }
}
