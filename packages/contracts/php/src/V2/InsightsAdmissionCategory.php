<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical InsightsAdmissionCategory wire values. */
enum InsightsAdmissionCategory: string {
    case UNAUTHENTICATED = 'UNAUTHENTICATED';
    case POLICY_DENIED = 'POLICY_DENIED';
    case APPROVAL_REQUIRED = 'APPROVAL_REQUIRED';
    case RATE_LIMITED = 'RATE_LIMITED';
    case KILL_SWITCH = 'KILL_SWITCH';
    case INVALID_REQUEST = 'INVALID_REQUEST';
}
