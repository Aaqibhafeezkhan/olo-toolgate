// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;
import io.ololabs.toolgate.contracts.*;
/** Dedicated effect-capability key boundary, independent from policy and identity signing keys. */
public interface EffectSigner {
    EnterpriseSignedPermit sign(EnterprisePermitClaims claims);
    EnterprisePermitClaims verify(EnterpriseSignedPermit permit);
    /** v2 invocation permits for Tool Hosts (plan.md §11.2): the same key, typ toolgate-invocation-permit+jws. */
    default io.ololabs.toolgate.contracts.v2.SignedInvocationPermit signInvocation(io.ololabs.toolgate.contracts.v2.InvocationPermitClaims claims){throw Failure.unavailable();}
    default io.ololabs.toolgate.contracts.v2.InvocationPermitClaims verifyInvocation(io.ololabs.toolgate.contracts.v2.SignedInvocationPermit permit){throw Failure.unavailable();}
}
