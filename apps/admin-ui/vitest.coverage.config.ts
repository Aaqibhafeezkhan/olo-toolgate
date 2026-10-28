// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config';
import base from './vitest.config';
export default defineConfig({
  ...base,
  test: {
    ...base.test,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.d.ts'],
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      reportsDirectory: '../../build/coverage/typescript',
    },
  },
});
