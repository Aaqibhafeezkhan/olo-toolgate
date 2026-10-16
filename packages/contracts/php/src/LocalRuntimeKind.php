<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical LocalRuntimeKind wire values. */
enum LocalRuntimeKind: string {
    case NATIVE = 'NATIVE';
    case PYTHON = 'PYTHON';
    case NODE = 'NODE';
    case POWERSHELL = 'POWERSHELL';
    case BATCH = 'BATCH';
    case SHELL = 'SHELL';
    case JAVA_JAR = 'JAVA_JAR';
    case JAVA_SDK = 'JAVA_SDK';
    case DOTNET = 'DOTNET';
    case WASM = 'WASM';
}
