// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { LocalToolInfo, ControlDevice, ControlExecutionBinding } from '@olo-labs/toolgate-contracts';
import type { RunEvent, RunEventPage } from '@olo-labs/toolgate-contracts/v2';
import { ApiError, ControlClient } from './api';
import { Failure } from './Failure';
import { testDefaults } from './testDefaults';

type Run = { id: string; startedAt: number; endedAt?: number; state: string; terminal: boolean; events: RunEvent[]; truncated: boolean; droppedFrames: number; expired: boolean; gaps: RunEventPage['gaps']; reconnecting: boolean };
const POLL_MS = 750;
const MAX_RETRY_MS = 8000;
const label = (event: RunEvent) => event.source === 'PLATFORM' ? 'Control' : event.executorId ? `Executor ${event.executorId}` : 'Executor';
const time = (ms: number) => new Date(ms).toISOString().slice(11, 23);

/** The final response as text: the tool's text content, else its structured content. */
export function responseText(event: RunEvent | undefined): string | undefined {
  const result = event?.result; if (!result) return undefined;
  const text = result.content.map(item => item.type === 'text' && typeof item.text === 'string' ? item.text : '').filter(Boolean).join('\n');
  return text || (result.structuredContent ? JSON.stringify(result.structuredContent) : undefined);
}
function describe(event: RunEvent): string {
  switch (event.kind) {
    case 'STATE': return `Invocation ${event.state?.toLowerCase().replace(/_/g, ' ') ?? 'changed'}`;
    case 'LOG': return `${event.level ?? 'info'}: ${event.message ?? ''}`;
    case 'PROGRESS': return `Progress ${Math.round((event.fraction ?? 0) * 100)}%${event.message ? ` — ${event.message}` : ''}`;
    case 'INPUT_REQUEST': return `Input requested (${event.requestId ?? 'request'})`;
    case 'RESULT': return event.result?.isError ? `Error: ${event.result.error?.code ?? 'TOOL_ERROR'} — ${event.result.error?.message ?? responseText(event) ?? ''}` : `Result: ${responseText(event) ?? ''}`;
    default: return event.kind;
  }
}

/** One Test panel for every entry point: the verified definition supplies the input, the normal human invocation API
 *  runs it under current grants, approvals and permits, and the timeline shows only the events Control retained for
 *  that run. Reconnecting resumes the same invocation from its cursor; it never submits another call. */
export function ToolTest({ client, deviceId: fixedDevice }: { client: ControlClient; deviceId?: string }) {
  const [bindings, setBindings] = useState<readonly ControlExecutionBinding[]>([]); const [devices, setDevices] = useState<readonly ControlDevice[]>([]);
  const [bindingId, setBindingId] = useState(''); const [deviceId, setDeviceId] = useState(fixedDevice ?? '');
  const [tools, setTools] = useState<readonly LocalToolInfo[]>(); const [toolKey, setToolKey] = useState('');
  const [input, setInput] = useState('{}'); const [missing, setMissing] = useState<readonly string[]>([]);
  const [run, setRun] = useState<Run>(); const [error, setError] = useState<unknown>(); const [busy, setBusy] = useState(false);
  const [json, setJson] = useState(false); const [follow, setFollow] = useState(true);
  const retry = useRef<{ body: string; key: string } | undefined>(undefined);
  const stream = useRef<AbortController | undefined>(undefined);
  const timeline = useRef<HTMLOListElement>(null);

  useEffect(() => { const controller = new AbortController();
    Promise.all([client.list('bindings', undefined, controller.signal), fixedDevice ? Promise.resolve({ items: [] }) : client.list('devices', undefined, controller.signal)])
      .then(([b, d]) => { if (controller.signal.aborted) return; setBindings(b.items.filter(item => item.enabled)); setDevices(d.items.filter(item => item.enabled)); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure); });
    return () => { controller.abort(); stream.current?.abort(); }; }, [client, fixedDevice]);
  useEffect(() => { if (follow && timeline.current) timeline.current.scrollTop = timeline.current.scrollHeight; }, [run?.events.length, follow]);

  const selected = tools?.find(tool => `${tool.toolId}\n${tool.action}` === toolKey);
  function reset(tool = selected) { if (!tool) return; const resolved = testDefaults(tool.inputSchema); setInput(JSON.stringify(resolved.value, null, 2)); setMissing(resolved.missing); retry.current = undefined; }

  async function discover(event: FormEvent) { event.preventDefault(); if (busy || !bindingId || !deviceId) return; setBusy(true); setError(undefined); setTools(undefined); setToolKey('');
    try { const catalog = await client.humanCatalog({ bindingId, deviceId }); setTools(catalog.tools);
      const first = catalog.tools[0]; if (first) { setToolKey(`${first.toolId}\n${first.action}`); reset(first); } }
    catch (failure) { setError(failure); } finally { setBusy(false); } }

  async function follow_(id: string, signal: AbortSignal) {
    let cursor = 0; let delay = POLL_MS; const seen = new Set<number>();
    for (;;) {
      if (signal.aborted) return;
      try {
        const page = await client.runEvents(id, cursor, signal);
        const fresh = page.events.filter(event => !seen.has(event.cursor)); fresh.forEach(event => seen.add(event.cursor));
        cursor = page.nextCursor; delay = POLL_MS;
        setRun(previous => previous && previous.id === id ? { ...previous, state: page.state, terminal: page.terminal, events: [...previous.events, ...fresh], truncated: page.truncated,
          droppedFrames: page.droppedFrames, expired: page.expired, gaps: page.gaps, reconnecting: false, endedAt: page.terminal && !page.more ? previous.endedAt ?? Date.now() : previous.endedAt } : previous);
        if (page.more) continue;
        if (page.terminal) return;
      } catch (failure) {
        if (signal.aborted) return;
        // Access ended or the run is gone: stop following, and say so.
        if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) { setError(failure); setRun(previous => previous && { ...previous, reconnecting: false }); return; }
        setRun(previous => previous && { ...previous, reconnecting: true }); delay = Math.min(delay * 2, MAX_RETRY_MS);
      }
      await new Promise(resolve => setTimeout(resolve, delay));
    }
  }

  async function test(event: FormEvent) { event.preventDefault(); if (busy || !selected) return; setError(undefined);
    let argumentsValue: unknown;
    try { argumentsValue = JSON.parse(input); } catch { setError(new ApiError(400, 'INVALID_JSON')); return; }
    if (!argumentsValue || typeof argumentsValue !== 'object' || Array.isArray(argumentsValue)) { setError(new ApiError(400, 'INVALID_REQUEST')); return; }
    const body = { bindingId, deviceId, request: { toolId: selected.toolId, action: selected.action, arguments: argumentsValue as Record<string, unknown> } };
    const exact = JSON.stringify(body);
    // The same request keeps its key, so a retry after an ambiguous failure or an approval is the same invocation.
    if (retry.current?.body !== exact) retry.current = { body: exact, key: crypto.randomUUID() };
    setBusy(true); stream.current?.abort();
    try {
      const outcome = await client.invokeHumanTool(body, retry.current.key); const startedAt = Date.now();
      const controller = new AbortController(); stream.current = controller;
      setRun({ id: outcome.invocation.id, startedAt, state: outcome.invocation.state, terminal: false, events: [], truncated: false, droppedFrames: 0, expired: false, gaps: [], reconnecting: false });
      void follow_(outcome.invocation.id, controller.signal);
    } catch (failure) { setError(failure); } finally { setBusy(false); }
  }

  const final = run?.events.filter(e => e.kind === 'RESULT').at(-1);
  const response = responseText(final);
  return <section aria-labelledby="tool-test-heading">
    <h2 id="tool-test-heading">Test a tool</h2>
    <p>Choose the execution binding you use and a target. Only tools the target reports for that binding, matching their verified package definition, are offered. Testing uses your current grants, approvals and permits; it is an ordinary invocation.</p>
    {Boolean(error) && <Failure error={error} />}
    <form className="user-form" onSubmit={discover}>
      <label htmlFor="test-binding">Execution binding</label>
      <select id="test-binding" value={bindingId} onChange={e => { setBindingId(e.target.value); setTools(undefined); }} disabled={busy}><option value="">Select a binding</option>{bindings.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select>
      {!fixedDevice && <><label htmlFor="test-device">Run on</label>
        <select id="test-device" value={deviceId} onChange={e => { setDeviceId(e.target.value); setTools(undefined); }} disabled={busy}><option value="">Select a target</option>{devices.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}</select></>}
      <button disabled={busy || !bindingId || !deviceId}>Show available tools</button>
    </form>
    {tools && !tools.length && <p role="status">This target reports no tools you can run through this binding. A ClientTool is offered only on client devices; check the target is online, the package is installed and your grants cover it.</p>}
    {tools && tools.length > 0 && <form className="user-form" onSubmit={test}>
      <label htmlFor="test-tool">Tool</label>
      <select id="test-tool" value={toolKey} onChange={e => { setToolKey(e.target.value); reset(tools.find(t => `${t.toolId}\n${t.action}` === e.target.value)); }} disabled={busy}>{tools.map(t => <option key={`${t.toolId}\n${t.action}`} value={`${t.toolId}\n${t.action}`}>{t.toolId}</option>)}</select>
      {selected && <p>{selected.description}</p>}
      <label htmlFor="test-input">Input (JSON object, from the tool's defaults)</label>
      <textarea id="test-input" rows={6} maxLength={60000} value={input} onChange={e => setInput(e.target.value)} disabled={busy} />
      {missing.length > 0 && <p role="note">Required without a default: {missing.join(', ')}. Enter values before testing.</p>}
      <div className="actions"><button className="primary" disabled={busy}>Test</button><button type="button" onClick={() => reset()} disabled={busy}>Reset to defaults</button></div>
    </form>}
    {run && <section aria-label="Run responses">
      <h3>Run responses</h3>
      <p>Invocation <code>{run.id}</code>{run.events.find(e => e.executorId)?.executorId ? <> on <code>{run.events.find(e => e.executorId)?.executorId}</code></> : null} — {run.state.toLowerCase().replace(/_/g, ' ')}{run.endedAt ? ` after ${run.endedAt - run.startedAt} ms` : ''}</p>
      <p role="status" aria-live="polite">{run.reconnecting ? 'Connection lost; resuming the same run from the last received event.' : run.terminal ? 'Run finished.' : run.state === 'PENDING_APPROVAL' ? 'Waiting for approval. An independent reviewer decides in Approvals; then press Test again with the same input to continue this request.' : 'Waiting for responses…'}</p>
      {run.expired && <p role="alert">Retention for this run has ended; its events are no longer available.</p>}
      {run.truncated && <p role="alert">Some responses exceeded the retained limits and are not shown.</p>}
      {run.droppedFrames > 0 && <p role="alert">The executor dropped {run.droppedFrames} response frame(s) before delivery.</p>}
      {run.gaps.length > 0 && <p role="alert">Missing responses: {run.gaps.map(g => g.fromSequence === g.toSequence ? `#${g.fromSequence}` : `#${g.fromSequence}–${g.toSequence}`).join(', ')}.</p>}
      <label><input type="checkbox" checked={follow} onChange={e => setFollow(e.target.checked)} /> Follow new responses</label>
      <ol ref={timeline} className="run-timeline" aria-label="Run timeline">{run.events.map(e => <li key={e.cursor}><time>{time(e.recordedAtUnixMs)}</time> <strong>{label(e)}</strong> {describe(e)}</li>)}</ol>
      {final && <section aria-label="Response"><h3>Response</h3><p>{response}</p>
        <div className="actions"><button type="button" onClick={() => setJson(v => !v)}>{json ? 'Hide JSON' : 'View JSON'}</button><button type="button" onClick={() => void navigator.clipboard?.writeText(json ? JSON.stringify(final.result, null, 2) : response ?? '')}>Copy response</button></div>
        {json && <pre>{JSON.stringify(final.result, null, 2)}</pre>}</section>}
    </section>}
  </section>;
}
