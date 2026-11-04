<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical RecoveryReadinessReason wire values. */
enum RecoveryReadinessReason: string {
    case ELIGIBLE = 'ELIGIBLE';
    case DISABLED = 'DISABLED';
    case MISSING_PROFILE = 'MISSING_PROFILE';
    case PROFILE_CHANGED = 'PROFILE_CHANGED';
    case NO_WRITE_ROUTES = 'NO_WRITE_ROUTES';
    case UNREVIEWED_ROUTE = 'UNREVIEWED_ROUTE';
    case MISSING_GUARANTEE = 'MISSING_GUARANTEE';
    case REVIEW_EXPIRED = 'REVIEW_EXPIRED';
    case INDEPENDENT_REVIEW_REQUIRED = 'INDEPENDENT_REVIEW_REQUIRED';
    case UNSUPPORTED_CREDENTIAL = 'UNSUPPORTED_CREDENTIAL';
    case UNQUALIFIED_EXECUTOR = 'UNQUALIFIED_EXECUTOR';
}
