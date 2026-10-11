// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// Git supplies the final message here; pre-commit cannot inspect it reliably.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
function git(args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  if (result.error || result.status !== 0) {
    throw Error(result.error?.message || result.stderr.trim() || `Git check failed: ${args.join(' ')}`);
  }
  return result.stdout.trim();
}
function requireStagedSource() {
  git(['diff', '--exit-code', '--quiet']);
  if (git(['ls-files', '--others', '--exclude-standard', '-z'])) {
    throw Error('Stage or ignore untracked source before an RC: commit.');
  }
}

try {
  if (!process.argv[2]) throw Error('Usage: node tools/ci/commit-msg.mjs COMMIT_MESSAGE_FILE');
  const subject = readFileSync(process.argv[2], 'utf8').split(/\r?\n/, 1)[0];
  if (!subject.startsWith('RC:')) process.exit(0);

  console.log('RC: commit: auditing and replaying every locally supported CI job.');
  try { requireStagedSource(); }
  catch (error) {
    throw Error(`RC: checks require source to match the commit index. Stage your changes first. ${error.message}`);
  }
  const tree = git(['write-tree']);
  // Git exports index/repository variables to hooks. Do not leak the committing
  // index into the runner's disposable snapshot or its regression-test repos.
  const environment = { ...process.env };
  for (const name of git(['rev-parse', '--local-env-vars']).split('\n')) delete environment[name.trim()];
  const python = process.env.TOOLGATE_CI_PYTHON
    || [resolve(root, '.venv/Scripts/python.exe'), resolve(root, '.venv/bin/python')].find(existsSync)
    || (process.platform === 'win32' ? 'python' : 'python3');
  const args = ['tools/ci/local.py', 'run', '--replayable'];
  if (process.env.TOOLGATE_CI_ACT) args.push('--act', process.env.TOOLGATE_CI_ACT);
  const result = spawnSync(python, args, { cwd: root, env: environment, stdio: 'inherit' });
  if (result.error) console.error(result.error.message);
  if (result.error || result.status !== 0) {
    console.error('RC: commit blocked by local CI. See build/local-ci/ and tools/ci/README.md.');
    process.exit(result.status || 1);
  }
  requireStagedSource();
  if (tree !== git(['write-tree'])) throw Error('The commit index changed during local CI; rerun the commit.');
  console.log('RC: local CI passed for the staged source. Hosted coverage is still required.');
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
