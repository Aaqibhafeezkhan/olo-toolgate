// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('v2 JSON corpus survives the JavaScript wire transport', async () => {
  const fixtures = JSON.parse(readFileSync(new URL('../../../../tests/fixtures/contracts/v2/valid.json', import.meta.url)));
  for (const [name, fixture] of Object.entries(fixtures)) {
    assert.deepEqual(JSON.parse(JSON.stringify(fixture)), fixture, name);
  }
  // The v2 subpath resolves through the package export map.
  const v2 = await import('@olo-labs/toolgate-contracts/v2');
  assert.equal(typeof v2, 'object');
});
