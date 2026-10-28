<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ControlErrorDetailCode wire values. */
enum ControlErrorDetailCode: string {
    case UNRESOLVED_IDENTITIES = 'UNRESOLVED_IDENTITIES';
    case REPLACE_CONFLICTS = 'REPLACE_CONFLICTS';
    case ONE_DEVICE_PER_ACCOUNT = 'ONE_DEVICE_PER_ACCOUNT';
    case REQUIRED_REVIEWS_CHANGE = 'REQUIRED_REVIEWS_CHANGE';
    case RATE_LIMITED = 'RATE_LIMITED';
}
