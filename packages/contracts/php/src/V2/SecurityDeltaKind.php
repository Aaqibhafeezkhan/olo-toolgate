<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical SecurityDeltaKind wire values. */
enum SecurityDeltaKind: string {
    case PERMISSION = 'PERMISSION';
    case DESTINATION = 'DESTINATION';
    case ROUTE = 'ROUTE';
    case SECRET = 'SECRET';
    case CONNECTION = 'CONNECTION';
    case CLAIM = 'CLAIM';
    case RUNTIME_TIER = 'RUNTIME_TIER';
    case PUBLISHER_KEY = 'PUBLISHER_KEY';
    case BASE_IMAGE = 'BASE_IMAGE';
    case KIND = 'KIND';
    case EFFECT_SAFETY = 'EFFECT_SAFETY';
    case BUSINESS_KEY_NAMESPACE = 'BUSINESS_KEY_NAMESPACE';
    case UNCLASSIFIED = 'UNCLASSIFIED';
}
