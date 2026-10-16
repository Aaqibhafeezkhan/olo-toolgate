<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical BrokerOperation wire values. */
enum BrokerOperation: string {
    case ROUTE_CALL = 'ROUTE_CALL';
    case SECRET_REDEEM = 'SECRET_REDEEM';
    case FILES_LIST = 'FILES_LIST';
    case FILES_READ = 'FILES_READ';
    case FILES_INFO = 'FILES_INFO';
    case FILES_WRITE = 'FILES_WRITE';
    case FILES_APPEND = 'FILES_APPEND';
    case FILES_MKDIR = 'FILES_MKDIR';
    case FILES_RENAME = 'FILES_RENAME';
}
