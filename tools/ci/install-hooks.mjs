// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';

if (process.env.CI) process.exit(0);
const repository = spawnSync('git',['rev-parse','--show-toplevel'],{encoding:'utf8'});
if (repository.status !== 0) process.exit(0); // Source archives have no Git hooks.
const current = spawnSync('git',['config','--get','core.hooksPath'],{encoding:'utf8'}).stdout.trim();
if (current && current !== '.githooks') {
  console.error(`Existing hooks path ${current} retained. Add "node tools/ci/precommit.mjs" to pre-commit and 'node tools/ci/commit-msg.mjs "$1"' to commit-msg.`);
  process.exit(1);
}
const result = spawnSync('git',['config','--local','core.hooksPath','.githooks']);
if (result.status !== 0) process.exit(result.status ?? 1);
console.log('Installed ToolGate pre-commit checks and RC: commit-message CI replay gate (Docker required).');
