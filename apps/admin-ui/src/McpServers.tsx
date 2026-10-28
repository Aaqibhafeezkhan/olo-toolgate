// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { McpApproval, McpServerRegistration, McpSnapshot, McpToolDefinition } from '@olo-labs/toolgate-contracts/v2';
import { ControlClient } from './api';
import { Failure } from './Failure';
import { Table } from './Table';
import { usePermissions } from './Permissions';

/** The existing manifest and deployment cap: a server with more tools to approve needs another registration. */
export const MAX_APPROVED_TOOLS = 64;
const LOCAL_NAME = /^[a-z][a-z0-9_-]{0,47}(\.[a-z][a-z0-9_-]{0,47}){0,3}$/;
const SLUG = /^[a-z][a-z0-9-]{1,30}$/;

/** A suggested ToolGate local name for an upstream tool: lowercase, other characters become '-'. The administrator
 *  can change it; Control refuses invalid or colliding names rather than renaming them. */
export function localName(upstream: string): string {
  const name = upstream.toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^[^a-z]+/, '').replace(/-+$/, '').slice(0, 48);
  return name || 'tool';
}

export type Change = 'new' | 'changed' | 'unchanged' | 'removed';
export interface DiffRow { name: string; change: Change; definition?: McpToolDefinition }
/** Each tool of a snapshot against the approval in force, by upstream name: new, changed (another definition digest)
 *  or unchanged; approved tools the snapshot no longer lists are removed. */
export function diff(snapshot: McpSnapshot, approval?: McpApproval): DiffRow[] {
  const approved = new Map((approval?.tools ?? []).map(t => [t.upstreamName, t.definitionDigest]));
  const rows: DiffRow[] = snapshot.tools.map(definition => ({
    name: definition.tool.name, definition,
    change: !approved.has(definition.tool.name) ? 'new' : approved.get(definition.tool.name) === definition.definitionDigest ? 'unchanged' : 'changed',
  }));
  const listed = new Set(snapshot.tools.map(d => d.tool.name));
  return [...rows, ...[...approved.keys()].filter(name => !listed.has(name)).map(name => ({ name, change: 'removed' as const }))];
}

function verification(server: McpServerRegistration): string {
  const v = server.verification;
  if (v.status === 'CURRENT') return `Current${v.checkedAtUnixMs ? `, checked ${new Date(v.checkedAtUnixMs).toLocaleString()}` : ''}`;
  return v.failure ? `Unverified: ${v.failure}` : 'Unverified';
}

/** Retries of one change reuse its idempotency key; another change gets a new one. */
function useKeys() {
  const pending = useRef<{ body: string; key: string } | undefined>(undefined);
  return (body: string) => { if (pending.current?.body !== body) pending.current = { body, key: crypto.randomUUID() }; return pending.current.key; };
}

/** Upstream MCP servers of this tenant (docs/planning/mcp-proxy/plan.md §7): register one by URL, scan it on its pool,
 *  review the definitions against the approved ones, approve up to 64 tools, and follow its state. */
export function McpServers({ client }: { client: ControlClient }) {
  const { can } = usePermissions(client), canRegister = can('mcp_server.register');
  const [servers, setServers] = useState<readonly McpServerRegistration[]>();
  const [error, setError] = useState<unknown>(), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState<string>();
  const [form, setForm] = useState({ slug: '', url: 'https://', secretName: '', scheme: 'BEARER' as 'BEARER' | 'HEADER', header: '', bindingId: '', strict: false });
  const key = useKeys();
  useEffect(() => {
    const abort = new AbortController(); setError(undefined);
    client.mcpServers(abort.signal).then(page => { if (!abort.signal.aborted) setServers(page.items); }).catch(e => { if (!abort.signal.aborted) setError(e); });
    return () => abort.abort();
  }, [client, attempt]);
  async function mutate(action: () => Promise<string>) {
    if (busy) return; setBusy(true); setError(undefined); setNotice('');
    try { setNotice(await action()); setAttempt(a => a + 1); } catch (e) { setError(e); } finally { setBusy(false); }
  }
  function register(event: FormEvent) {
    event.preventDefault(); if (!canRegister) return;
    const body = { slug: form.slug, url: form.url, auth: { secretName: form.secretName, scheme: form.scheme, ...(form.scheme === 'HEADER' ? { header: form.header } : {}) }, bindingId: form.bindingId, strict: form.strict };
    void mutate(async () => { const s = await client.registerMcpServer(body, key(JSON.stringify(body))); setSelected(s.slug); return `Registered ${s.slug}. Scan it to see its tools.`; });
  }
  const field = (name: keyof typeof form) => (event: { target: { value: string } }) => setForm(f => ({ ...f, [name]: event.target.value }));
  return <><p className="eyebrow">Administration</p><h1>MCP servers</h1>
    <p className="intro">Govern an MCP server you already run. ToolGate scans it from a Tool Host of the execution binding's Device Group, never from Control. Approved tools become a package of this tenant that starts disabled; enable, group and grant them like any tool. Every call runs on that pool, and a changed definition, server or TLS key quarantines it.</p>
    {Boolean(error) && <Failure error={error} retry={() => setAttempt(a => a + 1)} />}
    {notice && <p role="status">{notice}</p>}
    {!servers ? <p role="status">Loading MCP servers…</p> : servers.length === 0 ? <p>No MCP servers registered yet.</p> :
      <div className="table-wrap"><Table><caption>Registered MCP servers</caption>
        <thead><tr><th scope="col">Server</th><th scope="col">URL</th><th scope="col">State</th><th scope="col">Approval</th><th scope="col">Verification</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
        <tbody>{servers.map(s => <tr key={s.slug}><th scope="row">{s.slug}</th><td><code>{s.url}</code></td><td>{s.state}{s.quarantine ? ` (${s.quarantine.reason})` : ''}</td>
          <td>{s.approvalSequence ? `#${s.approvalSequence}` : 'None'}</td><td>{verification(s)}</td>
          <td><button type="button" onClick={() => setSelected(s.slug)} aria-pressed={selected === s.slug}>Review {s.slug}</button></td></tr>)}</tbody></Table></div>}
    {selected && <McpServerReview key={`${selected}-${attempt}`} client={client} slug={selected} canRegister={canRegister} busy={busy} mutate={mutate} onRemoved={() => setSelected(undefined)} />}
    <section className="guidance"><h2>Register an MCP server</h2><form onSubmit={register}>
      <label htmlFor="mcp-slug">Name (2–31 lowercase letters, digits or hyphens; its tools are mcp.name/…)</label>
      <input id="mcp-slug" required pattern={SLUG.source} maxLength={31} value={form.slug} readOnly={!canRegister} onChange={field('slug')} />
      <label htmlFor="mcp-url">HTTPS URL</label>
      <input id="mcp-url" type="url" required pattern="https://.+" maxLength={2048} value={form.url} readOnly={!canRegister} onChange={field('url')} />
      <label htmlFor="mcp-binding">Execution binding (its Device Group is the pool that scans and calls)</label>
      <input id="mcp-binding" required maxLength={128} value={form.bindingId} readOnly={!canRegister} onChange={field('bindingId')} />
      <label htmlFor="mcp-secret">Vault secret (in exactly the binding's Tool Group and Device Group)</label>
      <input id="mcp-secret" required maxLength={128} value={form.secretName} readOnly={!canRegister} onChange={field('secretName')} />
      <label htmlFor="mcp-scheme">Credential</label>
      <select id="mcp-scheme" value={form.scheme} disabled={!canRegister} onChange={e => setForm(f => ({ ...f, scheme: e.target.value as 'BEARER' | 'HEADER' }))}><option value="BEARER">Bearer token</option><option value="HEADER">Header</option></select>
      {form.scheme === 'HEADER' && <><label htmlFor="mcp-header">Header name</label><input id="mcp-header" required maxLength={64} value={form.header} readOnly={!canRegister} onChange={field('header')} /></>}
      <label><input type="checkbox" checked={form.strict} disabled={!canRegister} onChange={e => setForm(f => ({ ...f, strict: e.target.checked }))} /> Strict: check the upstream's tool list before every call</label>
      <button disabled={busy || !canRegister}>Register</button></form></section>
  </>;
}

interface ReviewProps { client: ControlClient; slug: string; canRegister: boolean; busy: boolean; mutate: (action: () => Promise<string>) => Promise<void>; onRemoved: () => void }

/** One server: its state and quarantine evidence, its latest snapshot against the approval in force, approval history. */
function McpServerReview({ client, slug, canRegister, busy, mutate, onRemoved }: ReviewProps) {
  const [server, setServer] = useState<McpServerRegistration>(), [snapshot, setSnapshot] = useState<McpSnapshot>(), [approvals, setApprovals] = useState<readonly McpApproval[]>([]);
  const [error, setError] = useState<unknown>(), [names, setNames] = useState<Record<string, string>>({}), [confirm, setConfirm] = useState(false);
  const key = useKeys();
  useEffect(() => {
    const abort = new AbortController();
    Promise.all([client.mcpServer(slug, abort.signal), client.mcpApprovals(slug, abort.signal)]).then(async ([s, a]) => {
      const latest = s.latestSnapshotRevision ? await client.mcpSnapshot(slug, s.latestSnapshotRevision, abort.signal) : undefined;
      if (abort.signal.aborted) return;
      setServer(s); setApprovals(a.items); setSnapshot(latest);
      const current = a.items.find(x => x.sequence === s.approvalSequence);
      setNames(Object.fromEntries((current?.tools ?? []).map(t => [t.upstreamName, t.toolId.slice(t.toolId.indexOf('/') + 1)])));
    }).catch(e => { if (!abort.signal.aborted) setError(e); });
    return () => abort.abort();
  }, [client, slug]);
  if (error) return <Failure error={error} />;
  if (!server) return <p role="status">Loading {slug}…</p>;
  const current = approvals.find(a => a.sequence === server.approvalSequence);
  const rows = snapshot ? diff(snapshot, current) : [];
  const chosen = Object.entries(names);
  const local = chosen.map(([, name]) => name);
  const invalid = local.filter(n => !LOCAL_NAME.test(n) || local.indexOf(n) !== local.lastIndexOf(n));
  const overCap = chosen.length > MAX_APPROVED_TOOLS;
  function toggle(name: string, on: boolean) { setNames(n => { const next = { ...n }; if (on) next[name] = localName(name); else delete next[name]; return next; }); }
  function approve() {
    if (!snapshot || !canRegister || overCap || invalid.length || !chosen.length) return;
    const body = { snapshotRevision: snapshot.revision, tools: chosen.map(([upstreamName, name]) => ({ upstreamName, name })) };
    void mutate(async () => { const a = await client.approveMcpServer(slug, body, key(JSON.stringify(body))); return `Approved ${a.tools.length} tools of ${slug} as ${a.packageId} ${a.packageVersion}. New tools start disabled.`; });
  }
  return <section className="guidance" aria-labelledby="mcp-review-heading"><h2 id="mcp-review-heading">{slug}</h2>
    <dl><dt>State</dt><dd>{server.state}</dd><dt>URL</dt><dd><code>{server.url}</code></dd><dt>Pool</dt><dd>Device Group of binding {server.bindingId}</dd>
      <dt>Verification</dt><dd>{verification(server)}</dd><dt>Strict</dt><dd>{server.strict ? 'Yes' : 'No'}</dd></dl>
    {server.quarantine && <div role="alert"><h3>Quarantined: {server.quarantine.reason}</h3>
      <p>{server.quarantine.serverWide ? 'The whole server' : `Tools ${server.quarantine.toolIds.join(', ')}`} since {new Date(server.quarantine.atUnixMs).toLocaleString()} (approval #{server.quarantine.approvalSequence}, evidence {server.quarantine.evidenceId}). Re-scan and re-approve to recover.</p></div>}
    <p><button type="button" disabled={busy || !canRegister} onClick={() => void mutate(async () => { const job = await client.scanMcpServer(slug, crypto.randomUUID()); return `Scan ${job.scanId} queued on ${job.hostId ?? 'the pool'}.`; })}>Scan {slug}</button></p>
    {!snapshot ? <p>Not scanned yet.</p> : <>
      <div className="table-wrap"><Table><caption>Snapshot #{snapshot.revision} of {snapshot.serverInfo.name} {snapshot.serverInfo.version}, against approval {current ? `#${current.sequence}` : 'none'}</caption>
        <thead><tr><th scope="col">Approve</th><th scope="col">Upstream tool</th><th scope="col">Local name</th><th scope="col">Change</th><th scope="col">Description</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.name}>
          <td><input type="checkbox" aria-label={`Approve ${row.name}`} checked={row.name in names} disabled={!canRegister || row.change === 'removed'} onChange={e => toggle(row.name, e.target.checked)} /></td>
          <th scope="row">{row.name}</th>
          <td>{row.name in names ? <input aria-label={`Local name for ${row.name}`} value={names[row.name]} readOnly={!canRegister} maxLength={195} aria-invalid={invalid.includes(names[row.name])} onChange={e => setNames(n => ({ ...n, [row.name]: e.target.value }))} /> : '—'}</td>
          <td>{row.change}</td><td>{row.definition?.tool.description ?? ''}</td></tr>)}</tbody></Table></div>
      {overCap && <p role="alert">At most {MAX_APPROVED_TOOLS} tools per approval; {chosen.length} are selected. Register the server again under another name for the rest.</p>}
      {invalid.length > 0 && <p role="alert">Local names must be unique and lowercase ToolGate names: {invalid.join(', ')}.</p>}
      <p><button type="button" disabled={busy || !canRegister || overCap || invalid.length > 0 || chosen.length === 0} onClick={approve}>Approve {chosen.length} tools</button></p></>}
    <div className="table-wrap"><Table><caption>Approval history</caption>
      <thead><tr><th scope="col">Approval</th><th scope="col">Package</th><th scope="col">Snapshot</th><th scope="col">Tools</th><th scope="col">Approved</th></tr></thead>
      <tbody>{approvals.length === 0 ? <tr><td colSpan={5}>No approvals yet.</td></tr> : approvals.map(a => <tr key={a.sequence}><th scope="row">#{a.sequence}</th><td>{a.packageId} {a.packageVersion}</td><td>#{a.snapshotRevision}</td><td>{a.tools.map(t => t.toolId).join(', ')}</td><td>{new Date(a.approvedAtUnixMs).toLocaleString()}</td></tr>)}</tbody></Table></div>
    {canRegister && (!confirm ? <p><button type="button" disabled={busy} onClick={() => setConfirm(true)}>Unregister {slug}</button></p> :
      <p role="group" aria-label="Confirm unregister">Unregistering undeploys mcp.{slug} and disables its tools. <button type="button" disabled={busy} onClick={() => void mutate(async () => { await client.unregisterMcpServer(slug, crypto.randomUUID()); onRemoved(); return `Unregistered ${slug}.`; })}>Unregister now</button> <button type="button" onClick={() => setConfirm(false)}>Keep it</button></p>)}
  </section>;
}
