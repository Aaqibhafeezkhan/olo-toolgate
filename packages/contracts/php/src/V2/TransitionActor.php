<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TransitionActor wire values. */
enum TransitionActor: string {
    case GATEWAY = 'GATEWAY';
    case CONTROL = 'CONTROL';
    case TOOL_HOST = 'TOOL_HOST';
    case CLIENT = 'CLIENT';
    case ADMIN = 'ADMIN';
}
