// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type { KillIssuer, PackageManifest, ProgressFrame } from '../src/v2/index.js';

const progress = { kind: 'PROGRESS', fraction: 0.5 } satisfies ProgressFrame;
void progress;
// @ts-expect-error A progress frame cannot carry another frame kind.
const wrongKind: ProgressFrame = { kind: 'RESULT', fraction: 0.5 };
void wrongKind;
// @ts-expect-error The v2 manifest schema version is the literal 2.
const wrongVersion: PackageManifest['schemaVersion'] = 3;
void wrongVersion;
const breakGlass: KillIssuer = 'control-breakglass';
void breakGlass;
