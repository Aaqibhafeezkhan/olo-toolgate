<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerFrameKind wire values. */
enum TriggerFrameKind: string {
    case TRIGGER_START = 'TRIGGER_START';
    case EMIT_ACK = 'EMIT_ACK';
    case ACCEPTED_UPTO = 'ACCEPTED_UPTO';
    case CHECKPOINT_ACK = 'CHECKPOINT_ACK';
    case RECONCILE = 'RECONCILE';
    case PAUSE = 'PAUSE';
    case RESUME = 'RESUME';
    case STOP = 'STOP';
    case READY = 'READY';
    case EMIT = 'EMIT';
    case CHECKPOINT = 'CHECKPOINT';
    case HEALTH = 'HEALTH';
    case HEARTBEAT = 'HEARTBEAT';
    case EXIT = 'EXIT';
}
