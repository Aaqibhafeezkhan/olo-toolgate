<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ControlIdentityResolutionAction wire values. */
enum ControlIdentityResolutionAction: string {
    case EXISTING = 'EXISTING';
    case MAP = 'MAP';
    case PLACEHOLDER = 'PLACEHOLDER';
}
