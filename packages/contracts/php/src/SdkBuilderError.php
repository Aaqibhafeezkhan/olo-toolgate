<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical SdkBuilderError wire values. */
enum SdkBuilderError: string {
    case BUILD_FAILED = 'BUILD_FAILED';
    case AUTHORITY_REVOKED = 'AUTHORITY_REVOKED';
    case EXPIRED = 'EXPIRED';
    case VERSION_CONFLICT = 'VERSION_CONFLICT';
}
