// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ToolTest } from '../src/ToolTest';
import { ControlClient } from '../src/api';
import { conforms, testDefaults } from '../src/testDefaults';

const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

/** The Hello World ServerTool's input schema as the Java SDK generates it. */
const helloSchema = { type: 'object', additionalProperties: false, required: [], properties: { text: { type: 'string', default: 'World', maxLength: 128, description: 'Who to greet' } } };
const tool = { toolId: 'ololabs.hello-world/server_greet', action: 'invoke', description: 'Greet someone', enabled: true, inputSchema: helloSchema, toolDigest: 'a'.repeat(64), packageDigest: 'b'.repeat(64) };
const result = (text: string) => ({ kind: 'RESULT', resultType: 'complete', content: [{ type: 'text', text }], structuredContent: { message: text }, isError: false });

describe('test input defaults', () => {
  it('uses only defaults that validate and never invents required values', () => {
    expect(testDefaults(helloSchema)).toEqual({ value: { text: 'World' }, missing: [] });
    const schema = { type: 'object', required: ['path', 'options'], properties: {
      path: { type: 'string' }, tooLong: { type: 'string', maxLength: 2, default: 'abc' }, count: { type: 'integer', default: 3, minimum: 1 },
      options: { type: 'object', required: ['mode'], properties: { mode: { type: 'string', enum: ['a', 'b'], default: 'b' }, depth: { type: 'integer', default: 1.5 } } },
      linked: { $ref: '#/x', default: 'x' } } };
    expect(testDefaults(schema)).toEqual({ value: { count: 3, options: { mode: 'b' } }, missing: ['path'] });
    expect(testDefaults({ type: 'string' }).missing.length).toBe(1);
    expect(conforms({ type: 'string', pattern: '^[a-z]+$' }, 'abc')).toBe(true);
    expect(conforms({ type: 'array', items: { type: 'integer' }, maxItems: 2 }, [1, 2, 3])).toBe(false);
  });
});

describe('ToolTest', () => {
  it('runs the verified default through the normal invocation API and shows the actual responses in cursor order', async () => {
    let polls = 0; const keys: string[] = []; const bodies: unknown[] = [];
    const transport = vi.fn(async (input: string, init?: RequestInit) => {
      if (input.includes('/directory/bindings') || input.includes('/bindings')) return response({ items: [{ id: 'hello-binding', name: 'Hello binding', enabled: true, revision: 1, toolGroupId: 'g', deviceGroupId: 'd', actions: [], allowedPackageDigests: [], requireOnline: false, ownerDependency: false }] });
      if (input.includes('/devices')) return response({ items: [{ id: 'tool-host-1', name: 'Tool Host', enabled: true, revision: 1, ownerUserId: 'root' }] });
      if (input.endsWith('/human/catalog')) return response({ tools: [tool] });
      if (input.endsWith('/human/invocations')) { keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? ''); bodies.push(JSON.parse(String(init?.body))); return response({ invocation: { id: 'inv-1', state: 'QUEUED' } }); }
      if (input.includes('/invocations/inv-1/events')) {
        polls++;
        const after = new URL(input, 'http://x').searchParams.get('after');
        if (after === '0') return response({ invocationId: 'inv-1', state: 'EXECUTING', terminal: false, events: [
          { cursor: 1, source: 'PLATFORM', kind: 'STATE', state: 'QUEUED', recordedAtUnixMs: 1 },
          { cursor: 2, source: 'PLATFORM', kind: 'STATE', state: 'EXECUTING', recordedAtUnixMs: 2 },
          { cursor: 3, source: 'EXECUTOR', kind: 'LOG', level: 'info', message: 'greeting <b>World</b>', executorId: 'tool-host-1', attempt: 1, sequence: 1, recordedAtUnixMs: 3 }],
          nextCursor: 3, more: false, truncated: false, droppedFrames: 0, expired: false, gaps: [] });
        // A reconnect replays from the last cursor; a duplicate delivery must not be shown twice.
        return response({ invocationId: 'inv-1', state: 'SUCCEEDED', terminal: true, events: [
          { cursor: 3, source: 'EXECUTOR', kind: 'LOG', level: 'info', message: 'greeting <b>World</b>', executorId: 'tool-host-1', recordedAtUnixMs: 3 },
          { cursor: 4, source: 'EXECUTOR', kind: 'RESULT', executorId: 'tool-host-1', attempt: 1, recordedAtUnixMs: 4, result: result('Hello World') },
          { cursor: 5, source: 'PLATFORM', kind: 'STATE', state: 'SUCCEEDED', recordedAtUnixMs: 5 }],
          nextCursor: 5, more: false, truncated: false, droppedFrames: 0, expired: false, gaps: [] });
      }
      return response({}, 404);
    });
    vi.stubGlobal('fetch', transport);
    render(<ToolTest client={new ControlClient('session', () => {})} />);
    await screen.findByRole('option', { name: 'Hello binding' });
    fireEvent.change(screen.getByLabelText('Execution binding'), { target: { value: 'hello-binding' } });
    fireEvent.change(screen.getByLabelText('Run on'), { target: { value: 'tool-host-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show available tools' }));
    await waitFor(() => expect((screen.getByLabelText(/Input/) as HTMLTextAreaElement).value).toBe('{\n  "text": "World"\n}'));
    fireEvent.click(screen.getByRole('button', { name: 'Test' }));
    await screen.findByRole('region', { name: 'Response' }, { timeout: 4000 });
    expect(bodies[0]).toEqual({ bindingId: 'hello-binding', deviceId: 'tool-host-1', request: { toolId: 'ololabs.hello-world/server_greet', action: 'invoke', arguments: { text: 'World' } } });
    const items = screen.getByRole('list', { name: 'Run timeline' }).querySelectorAll('li');
    expect([...items].map(li => li.textContent?.replace(/^\S+ /, ''))).toEqual([
      'Control Invocation queued', 'Control Invocation executing', 'Executor tool-host-1 info: greeting <b>World</b>', 'Executor tool-host-1 Result: Hello World', 'Control Invocation succeeded']);
    // Tool output is text, never markup.
    expect(document.querySelector('.run-timeline b')).toBeNull();
    expect(screen.getByRole('region', { name: 'Response' }).textContent).toContain('Hello World');
    expect(polls).toBe(2);

    // Edited input gives a new request key; the same input keeps its key for an exact retry.
    fireEvent.change(screen.getByLabelText(/Input/), { target: { value: '{"text":"Anupriya"}' } });
    fireEvent.click(screen.getByRole('button', { name: 'Test' }));
    await waitFor(() => expect(keys.length).toBe(2));
    expect(keys[1]).not.toBe(keys[0]);
    expect((bodies[1] as { request: { arguments: unknown } }).request.arguments).toEqual({ text: 'Anupriya' });
  });

  it('stops following when access is revoked and reports gaps, truncation and drops explicitly', async () => {
    let calls = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      if (input.includes('/bindings')) return response({ items: [{ id: 'b', name: 'B', enabled: true }] });
      if (input.includes('/devices')) return response({ items: [{ id: 'host', name: 'Host', enabled: true }] });
      if (input.endsWith('/human/catalog')) return response({ tools: [tool] });
      if (input.endsWith('/human/invocations')) return response({ invocation: { id: 'inv-2', state: 'QUEUED' } });
      if (input.includes('/events')) { calls++; return calls === 1 ? response({ invocationId: 'inv-2', state: 'EXECUTING', terminal: false, events: [], nextCursor: 0, more: false, truncated: true, droppedFrames: 2, expired: false, gaps: [{ attempt: 1, fromSequence: 3, toSequence: 4 }] }) : response({ code: 'FORBIDDEN' }, 403); }
      return response({}, 404);
    }));
    render(<ToolTest client={new ControlClient('session', () => {})} />);
    await screen.findByRole('option', { name: 'B' });
    fireEvent.change(screen.getByLabelText('Execution binding'), { target: { value: 'b' } });
    fireEvent.change(screen.getByLabelText('Run on'), { target: { value: 'host' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show available tools' }));
    await screen.findByRole('button', { name: 'Test' });
    fireEvent.click(screen.getByRole('button', { name: 'Test' }));
    await screen.findByText(/Missing responses: #3–4/);
    expect(screen.getByText(/exceeded the retained limits/)).toBeTruthy();
    expect(screen.getByText(/dropped 2 response frame/)).toBeTruthy();
    await waitFor(() => expect(screen.getByText(/does not have permission/)).toBeTruthy(), { timeout: 4000 });
    const before = calls; await new Promise(resolve => setTimeout(resolve, 1000)); expect(calls).toBe(before);
  });

  it('offers no tools when the target reports none, and says why', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string) => input.includes('/bindings') ? response({ items: [{ id: 'b', name: 'B', enabled: true }] }) : input.endsWith('/human/catalog') ? response({ tools: [] }) : response({ items: [] })));
    render(<ToolTest client={new ControlClient('session', () => {})} deviceId="tool-host-1" />);
    await screen.findByRole('option', { name: 'B' });
    expect(screen.queryByLabelText('Run on')).toBeNull();
    fireEvent.change(screen.getByLabelText('Execution binding'), { target: { value: 'b' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show available tools' }));
    await screen.findByText(/A ClientTool is offered only on client devices/);
  });
});
