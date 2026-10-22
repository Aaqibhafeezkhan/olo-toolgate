// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { generateKeyPairSync, randomBytes } from 'node:crypto';

const root = resolve('.');
const work = mkdtempSync(join(tmpdir(), 'toolgate-staged-scan-'));
function scan(directory, expected = 0) {
  const result = spawnSync('docker', ['run', '--rm', '-v', `${directory.replaceAll('\\', '/')}:/repo:ro`,
    'zricethezav/gitleaks:v8.24.2', 'detect', '--source=/repo', '--no-git', '--redact', '--exit-code=1'], { encoding: 'utf8' });
  if (result.error || result.status !== expected) {
    throw new Error(`Secret scan failed (expected ${expected}, received ${result.status}). ${result.error?.message ?? ''}\n${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
}
try {
  // Scan the actual Git index, excluding ignored local credentials and build state.
  const staged = join(work, 'staged');
  mkdirSync(staged);
  const checkout = spawnSync('git', ['checkout-index', '--all', `--prefix=${staged.replaceAll('\\', '/')}/`], { encoding: 'utf8', cwd: root });
  if (checkout.status !== 0) throw new Error(checkout.stderr || 'Cannot export staged files');
  scan(staged);
  const proof = join(work, 'proof');
  mkdirSync(join(proof, 'apps/browser-extension'), { recursive: true });
  writeFileSync(join(proof, '.gitleaks.toml'), readFileSync(join(staged, '.gitleaks.toml')));
  const identity = join(proof, 'apps/browser-extension/identity.json');
  const publicIdentity = readFileSync(join(staged, 'apps/browser-extension/identity.json'));
  writeFileSync(identity, publicIdentity);
  scan(proof);
  writeFileSync(join(proof, 'unexpected.json'), publicIdentity);
  scan(proof, 1);
  rmSync(join(proof, 'unexpected.json'));
  // The allowance must not hide new secrets, even in the public identity file.
  const document = JSON.parse(publicIdentity);
  document.apiKey = 'gh' + 'p_' + randomBytes(18).toString('hex');
  writeFileSync(identity, JSON.stringify(document, null, 2));
  scan(proof, 1);
  writeFileSync(identity, generateKeyPairSync('rsa', { modulusLength: 2048,
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } }).privateKey);
  scan(proof, 1);
  // A discovery vector is public signature data; the allowance is limited to its jws field.
  const vector = join(proof, 'tests/fixtures/tenant-discovery/v2.json');
  mkdirSync(join(proof, 'tests/fixtures/tenant-discovery'), {recursive:true});
  const discovery = JSON.parse(readFileSync(join(staged, 'tests/fixtures/tenant-discovery/v2.json'))).cases.initial;
  writeFileSync(identity, publicIdentity);
  writeFileSync(vector, JSON.stringify(discovery, null, 2));
  scan(proof);
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const token = encode({alg:'HS256',typ:'JWT'})+'.'+encode({iss:'https://fixture.invalid',sub:'scanner',aud:'fixture',exp:2000000000})+'.'+randomBytes(32).toString('base64url');
  writeFileSync(vector, JSON.stringify({...discovery,credential:token},null,2));
  scan(proof,1);
  console.log('Staged secret scan and public-key/credential/private-key regression checks passed.');
} finally {
  // Only remove the temporary directory created above.
  rmSync(work, { recursive: true, force: true });
}
