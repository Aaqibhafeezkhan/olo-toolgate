<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical BusinessKeySource wire values. */
enum BusinessKeySource: string {
    case HEADER = 'HEADER';
    case ARGUMENT = 'ARGUMENT';
    case DEDUPE_WINDOW = 'DEDUPE_WINDOW';
}
