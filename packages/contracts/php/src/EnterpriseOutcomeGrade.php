<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseOutcomeGrade wire values. */
enum EnterpriseOutcomeGrade: string {
    case REPORTED = 'REPORTED';
    case CONTROL_PROVEN = 'CONTROL_PROVEN';
    case TARGET_CONFIRMED = 'TARGET_CONFIRMED';
    case REVIEWER_CONFIRMED = 'REVIEWER_CONFIRMED';
    case TARGET_DEDUPLICATION_ASSUMED = 'TARGET_DEDUPLICATION_ASSUMED';
}
