<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ValueType wire values. */
enum ValueType: string {
    case STRING = 'STRING';
    case INT = 'INT';
    case LONG = 'LONG';
    case NUMBER = 'NUMBER';
    case BOOLEAN = 'BOOLEAN';
    case DATE = 'DATE';
    case DATE_TIME = 'DATE_TIME';
    case DURATION = 'DURATION';
    case ENUM = 'ENUM';
    case URI = 'URI';
    case OBJECT = 'OBJECT';
    case ARRAY = 'ARRAY';
    case BINARY = 'BINARY';
}
