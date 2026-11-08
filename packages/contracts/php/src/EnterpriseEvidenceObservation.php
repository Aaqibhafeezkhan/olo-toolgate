<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseEvidenceObservation wire values. */
enum EnterpriseEvidenceObservation: string {
    case AUTHORIZATION_CONSUMED = 'AUTHORIZATION_CONSUMED';
    case BROKER_RESPONSE = 'BROKER_RESPONSE';
    case TARGET_OUTCOME = 'TARGET_OUTCOME';
    case RECONCILER_LOOKUP = 'RECONCILER_LOOKUP';
}
