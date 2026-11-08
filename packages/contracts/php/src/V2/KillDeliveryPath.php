<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical KillDeliveryPath wire values. */
enum KillDeliveryPath: string {
    case PUSH = 'PUSH';
    case CHECK_IN = 'CHECK_IN';
    case POLL = 'POLL';
}
