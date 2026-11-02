<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseAttemptEffectScope wire values. */
enum EnterpriseAttemptEffectScope: string {
    case ADMISSION_ONLY = 'ADMISSION_ONLY';
    case QUALIFIED_BROKER_ONLY = 'QUALIFIED_BROKER_ONLY';
    case REVIEWER_WITNESSED = 'REVIEWER_WITNESSED';
    case LEGACY_UNKNOWN = 'LEGACY_UNKNOWN';
}
