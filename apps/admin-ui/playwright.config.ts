// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from '@playwright/test';
import { createHash, X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';

// Trust the disposable fixture's server key only within this browser process.
// API requests separately verify the fixture CA through NODE_EXTRA_CA_CERTS.
const certificate = process.env.UI_TEST_TLS_CERTIFICATE;
const spki = certificate && createHash('sha256').update(
  new X509Certificate(readFileSync(certificate)).publicKey.export({type:'spki',format:'der'})
).digest('base64');
export default defineConfig({
  testDir: './tests/e2e', fullyParallel: false, workers: 1, retries: 0,
  timeout: 30000, reporter: [['line'],['json',{outputFile:'../../build/ui/browser-results.json'}]],
  outputDir: '../../build/ui/browser-output',
  use: { baseURL: process.env.UI_TEST_ORIGIN, browserName:'chromium', trace:'off', screenshot:'off', video:'off',
    launchOptions: {args: spki ? [`--ignore-certificate-errors-spki-list=${spki}`] : []} },
});
