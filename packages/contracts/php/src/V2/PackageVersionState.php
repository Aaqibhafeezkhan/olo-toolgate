<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical PackageVersionState wire values. */
enum PackageVersionState: string {
    case UPLOADED = 'UPLOADED';
    case VERIFIED = 'VERIFIED';
    case IN_REVIEW = 'IN_REVIEW';
    case APPROVED = 'APPROVED';
    case ROLLING_OUT = 'ROLLING_OUT';
    case ACTIVE = 'ACTIVE';
    case PAUSED = 'PAUSED';
    case ROLLED_BACK = 'ROLLED_BACK';
    case DEPRECATED = 'DEPRECATED';
    case DRAINING = 'DRAINING';
    case RETIRED = 'RETIRED';
    case REJECTED = 'REJECTED';
    case SUSPENDED = 'SUSPENDED';
}
