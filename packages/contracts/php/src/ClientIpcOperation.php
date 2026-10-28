<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ClientIpcOperation wire values. */
enum ClientIpcOperation: string {
    case HEALTH = 'HEALTH';
    case ENROLL = 'ENROLL';
    case CHECK_IN = 'CHECK_IN';
    case ACTIVITY = 'ACTIVITY';
    case POWER_ON = 'POWER_ON';
    case POWER_OFF = 'POWER_OFF';
}
