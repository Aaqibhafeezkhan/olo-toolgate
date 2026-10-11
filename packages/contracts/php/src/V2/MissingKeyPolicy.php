<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical MissingKeyPolicy wire values. */
enum MissingKeyPolicy: string {
    case REJECT = 'REJECT';
    case ASK = 'ASK';
    case ALLOW_WITH_DEDUPE = 'ALLOW_WITH_DEDUPE';
}
