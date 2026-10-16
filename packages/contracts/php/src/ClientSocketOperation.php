<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ClientSocketOperation wire values. */
enum ClientSocketOperation: string {
    case CHECK_IN = 'CHECK_IN';
    case AUTHORIZE = 'AUTHORIZE';
    case RESULT = 'RESULT';
}
