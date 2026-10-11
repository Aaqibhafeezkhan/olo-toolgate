<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical AuthType wire values. */
enum AuthType: string {
    case API_KEY = 'API_KEY';
    case BASIC = 'BASIC';
    case OAUTH2_CLIENT_CREDENTIALS = 'OAUTH2_CLIENT_CREDENTIALS';
    case TOKEN_EXCHANGE = 'TOKEN_EXCHANGE';
    case CONNECTION = 'CONNECTION';
    case CUSTOM = 'CUSTOM';
}
