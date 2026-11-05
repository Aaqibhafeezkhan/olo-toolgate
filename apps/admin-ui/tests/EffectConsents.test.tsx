// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import type {EffectConsentView} from '@olo-labs/toolgate-contracts/v2';
import {EffectConsents} from '../src/EffectConsents';
import {ControlClient} from '../src/api';
afterEach(cleanup);
const digest = 'sha256:'+'a'.repeat(64);
function preview(): EffectConsentView {
  return {previewDigest: digest, revision: 1, state: 'PENDING', preview: {
    consentId: 'consent', tenantId: 'tenant', operationId: 'operation', runId: 'operation',
    toolId: 'example.payments/pay', action: 'pay', argumentsDigest: digest, profileDigest: digest,
    resources: [], notBeforeUnixMs: Date.now()-1000, expiresAtUnixMs: Date.now()+60000,
    slots: [{slotIndex: 0, permittedCount: 1, request: {kind: 'MAPPED_HTTP', routeId: 'pay',
      classification: 'FINANCIAL', scheme: 'https', host: 'pay.example.test', port: 443,
      method: 'POST', path: '/pay', query: '', headers: {}, contentType: 'application/json',
      bodyMode: 'CANONICAL_JSON', body: {recipient: '<img src=x onerror=alert(1)>', amount: 5000, currency: 'USD'}}}]
  }};
}
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {status});
function mount(view = preview(), postStatus = 200, getStatus = 200) {
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url, options) => {
    if (options?.method === 'POST') return response({...view, state: 'APPROVED', revision: 2}, postStatus);
    if (String(url).includes('effect-consent')) return response(view, getStatus);
    return response({items: [{id: 'operation', state: 'PENDING_APPROVAL', evaluation: {toolId: view.preview.toolId}}]});
  });
  render(<EffectConsents client={new ControlClient('private-token', vi.fn(), fetcher)}/>);
  return fetcher;
}
async function select() {
  await screen.findByRole('option', {name: /operation · example.payments/});
  fireEvent.change(screen.getByRole('combobox'), {target: {value: 'operation'}});
}
it('shows actual mapped values as text and approves only the confirmed exact digest and revision', async () => {
  const fetcher = mount(); await select();
  await screen.findByText(/5000/);
  expect(document.querySelector('img')).toBeNull();
  const approve = screen.getByRole('button', {name: 'Approve exact effects'});
  expect(approve.hasAttribute('disabled')).toBe(true);
  fireEvent.click(screen.getByRole('checkbox')); fireEvent.click(approve);
  await screen.findByText('APPROVED');
  const posted = fetcher.mock.calls.find(([, options]) => options?.method === 'POST');
  expect(String(posted?.[0])).toContain('/operation/effect-consent/approve');
  expect(JSON.parse(String(posted?.[1]?.body))).toEqual({previewDigest: digest, expectedRevision: 1});
});
it('keeps expired consent closed', async () => {
  const original = preview(); const view = {...original, preview: {...original.preview, expiresAtUnixMs: Date.now()-1}};
  const fetcher = mount(view); await select(); await screen.findByText(/Expired/);
  expect(screen.getByRole('button', {name: 'Approve exact effects'}).hasAttribute('disabled')).toBe(true);
  expect(screen.getByRole('checkbox').hasAttribute('disabled')).toBe(true);
  expect(fetcher.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});
it('clears confirmation after a stale review response before reloading', async () => {
  mount(preview(), 409); await select(); await screen.findByText(/5000/);
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByRole('button', {name: 'Approve exact effects'}));
  await waitFor(() => expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false));
  expect(screen.getByRole('button', {name: 'Approve exact effects'}).hasAttribute('disabled')).toBe(true);
});
it('does not show a review action when no exact preview exists', async () => {
  mount(preview(), 200, 404); await select();
  await screen.findByText(/no exact-effect preview/);
  expect(screen.queryByRole('button', {name: 'Approve exact effects'})).toBeNull();
});
