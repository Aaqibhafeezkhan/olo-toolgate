<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical MappingKind wire values. */
enum MappingKind: string {
    case SECRET = 'SECRET';
    case GLOBAL = 'GLOBAL';
    case CLAIM = 'CLAIM';
    case DESTINATION = 'DESTINATION';
}
