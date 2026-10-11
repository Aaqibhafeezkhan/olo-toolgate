// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table} from './Table';
import { useEffect, useRef, useState } from 'react';
import type { EnterpriseApproval, EnterpriseApprovalPage, EnterpriseInvocation, EnterpriseReviewDecision } from '@olo-labs/toolgate-contracts';
import { ApiError, ControlClient } from './api';
import { Failure } from './Failure';

const labels = { PENDING: 'Pending review', APPROVED: 'Approved', DENIED: 'Denied', CANCELLED: 'Cancelled', EXPIRED: 'Expired', REVOKED: 'Revoked' };
const timestamp = (value: number) => new Date(value).toLocaleString();

export function Approvals({ client }: { client: ControlClient }) {
  const [page, setPage] = useState<EnterpriseApprovalPage>(); const [error, setError] = useState<unknown>();
  const [cursor, setCursor] = useState<string>(); const [history, setHistory] = useState<(string | undefined)[]>([]);
  const [attempt, setAttempt] = useState(0); const [selected, setSelected] = useState<string>();
  useEffect(() => {
    const abort = new AbortController(); let loading = false; setPage(undefined); setError(undefined);
    const load = async () => { if (loading) return; loading = true;
      try { const result = await client.approvals(cursor, abort.signal); if (!abort.signal.aborted) { setPage(result); setError(undefined); } }
      catch (failure) { if (!abort.signal.aborted) setError(failure); } finally { loading = false; }
    };
    void load(); const timer = window.setInterval(() => { void load(); }, 15000);
    return () => { abort.abort(); window.clearInterval(timer); };
  }, [client, cursor, attempt]);
  const refresh = () => setAttempt(value => value + 1);
  return <><div className="page-heading"><div><p className="eyebrow">Scoped human review</p><h1>Approvals</h1></div><button onClick={refresh}>Refresh approvals</button></div>
    <p className="intro">Each operation approval binds one invocation, all affected resources, its arguments digest, tool version and execution target. Every required policy obligation must be reviewed. Current grants and reviewer permissions are checked again before execution.</p>
    {Boolean(error) && <Failure error={error} retry={refresh} />}
    {!page && !error && <p role="status">Loading approvals…</p>}
    {page && <><div className="table-wrap"><Table><caption className="sr-only">Approval requests</caption><thead><tr><th>Request</th><th>Type</th><th>Invocation</th><th>Reviews</th><th>State</th><th>Expires</th></tr></thead><tbody>
      {page.items.map(a => <tr key={a.id}><th scope="row"><button className="record-link" onClick={() => setSelected(a.id)} aria-label={`Review approval ${a.id}`}>{a.id}</button></th><td>{a.approvalType}</td><td><code>{a.invocationId}</code></td><td>{a.reviews.filter(r => r.decision === 'APPROVE').length} / {a.obligationIds.length}</td><td>{labels[a.state]}</td><td data-sort-value={a.expiresAtUnixMs}>{timestamp(a.expiresAtUnixMs)}</td></tr>)}
    </tbody></Table></div>{page.items.length === 0 && <p>No approvals in your current scope.</p>}
      <div className="pagination"><button disabled={!history.length} onClick={() => { setCursor(history.at(-1)); setHistory(history.slice(0, -1)); }}>Previous approvals page</button><button disabled={!page.nextCursor} onClick={() => { setHistory([...history, cursor]); setCursor(page.nextCursor); }}>Next approvals page</button></div></>}
    {selected && <ApprovalDetails key={selected} client={client} id={selected} close={() => setSelected(undefined)} changed={refresh} />}
  </>;
}

function ApprovalDetails({ client, id, close, changed }: { client: ControlClient; id: string; close: () => void; changed: () => void }) {
  const [approval, setApproval] = useState<EnterpriseApproval>(); const [invocation, setInvocation] = useState<EnterpriseInvocation>();
  const [obligation, setObligation] = useState(''); const [decision, setDecision] = useState<EnterpriseReviewDecision>('APPROVE');
  const [confirmed, setConfirmed] = useState(false); const [busy, setBusy] = useState(false); const [error, setError] = useState<unknown>(); const [attempt, setAttempt] = useState(0);
  const pending = useRef<{ body: string; key: string } | undefined>(undefined); const locked = useRef(false); const alive = useRef(true);
  useEffect(() => { const abort = new AbortController(); alive.current = true; setError(undefined);
    async function load() { try { const a = await client.approval(id, abort.signal); const i = await client.invocation(a.invocationId, abort.signal);
      if (!abort.signal.aborted) { setApproval(a); setInvocation(i); setObligation(a.obligationIds.find(ob => !a.reviews.some(r => r.obligationId === ob)) ?? a.obligationIds[0] ?? ''); setConfirmed(false); }
    } catch (failure) { if (!abort.signal.aborted) setError(failure); } }
    void load(); return () => { alive.current = false; abort.abort(); };
  }, [client, id, attempt]);
  async function submit() {
    if (!approval || !invocation || !obligation || !confirmed || locked.current) return;
    const body = { expectedRevision: approval.revision, obligationId: obligation, decision }; const encoded = JSON.stringify(body);
    if (pending.current?.body !== encoded) pending.current = { body: encoded, key: crypto.randomUUID() };
    locked.current = true; setBusy(true); setError(undefined);
    try { const a = await client.decideApproval(id, body, pending.current.key); if (alive.current) { setApproval(a); setConfirmed(false); pending.current = undefined; changed(); setAttempt(value => value + 1); window.dispatchEvent(new Event('toolgate:approval-change')); } }
    catch (failure) { if (alive.current) { setError(failure); if (failure instanceof ApiError && failure.status === 409) { pending.current = undefined; setAttempt(value => value + 1); } } }
    finally { locked.current = false; if (alive.current) setBusy(false); }
  }
  async function cancel() { if (!invocation || locked.current || !confirmed) return; locked.current = true; setBusy(true);
    try { await client.cancelInvocation(invocation.id, invocation.revision); if (alive.current) { changed(); setAttempt(value => value + 1); window.dispatchEvent(new Event('toolgate:approval-change')); } }
    catch (failure) { if (alive.current) setError(failure); } finally { locked.current = false; if (alive.current) setBusy(false); }
  }
  const e = invocation?.evaluation; const expired = approval && approval.expiresAtUnixMs <= Date.now();
  return <section className="detail" aria-label="Approval details"><div className="page-heading"><h2>Review exact operation</h2><button disabled={busy} onClick={close}>Close approval</button></div>
    {Boolean(error) && <Failure error={error} />}
    {!approval || !e ? <p role="status">Loading approval details…</p> : <>
      <p><strong>{labels[approval.state]}</strong> · Revision {approval.revision} · Authorization epoch {approval.authorizationEpoch}</p>
      <dl className="policy-details"><dt>Mode</dt><dd>{e.context.mode}</dd><dt>Requester</dt><dd>{e.context.userId ?? 'Service workload'}</dd><dt>Agent</dt><dd>{e.context.agentId ?? 'Direct human'}</dd>
        <dt>Delegation chain</dt><dd>{e.context.chain.map(h => h.agentId).join(' → ') || 'No preceding hops'}</dd><dt>Tool and action</dt><dd>{e.toolId} / {e.action}</dd><dt>Binding and device</dt><dd>{e.context.bindingId} / {e.context.deviceId}</dd>
        <dt>All affected resources</dt><dd><ul>{e.resources.map(r => <li key={`${r.kind}:${r.locator}`}><code>{r.kind}: {r.locator}</code></li>)}</ul></dd>
        <dt>Arguments digest</dt><dd><code>{e.argumentsDigest}</code></dd><dt>Tool digest</dt><dd><code>{e.toolDigest}</code></dd><dt>Package digest</dt><dd><code>{e.packageDigest}</code></dd>
        <dt>Request binding digest</dt><dd><code>{approval.requestDigest}</code></dd><dt>Expires</dt><dd>{timestamp(approval.expiresAtUnixMs)}</dd><dt>Execution state</dt><dd>{invocation?.state}</dd></dl>
      <h3>Required reviews</h3><ul>{approval.obligationIds.map(ob => <li key={ob}><code>{ob}</code> · {approval.reviews.filter(r => r.obligationId === ob).map(r => `${r.decision} by ${r.reviewerUserId}`).join(', ') || 'Pending'}</li>)}</ul>
      {expired && <p role="status">This approval expired. Submit a new invocation for current review.</p>}
      {!expired && (approval.state === 'PENDING' || approval.state === 'APPROVED') && <fieldset disabled={busy}><legend>Review one obligation</legend>
        <label>Policy obligation<select value={obligation} onChange={event => { setObligation(event.target.value); setConfirmed(false); }}>{approval.obligationIds.map(ob => <option key={ob} value={ob}>{ob}</option>)}</select></label>
        <label>Decision<select value={decision} onChange={event => { setDecision(event.target.value as EnterpriseReviewDecision); setConfirmed(false); }}><option value="APPROVE">Approve this exact invocation</option><option value="DENY">Deny</option><option value="REVOKE">Revoke approval</option></select></label>
        <label className="checkbox"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />I reviewed the complete resource set, identities, versions and execution target.</label>
        <button className="primary" disabled={!confirmed || !obligation} onClick={() => { void submit(); }}>{busy ? 'Submitting decision…' : 'Confirm decision'}</button>
        <button className="danger" disabled={!confirmed} onClick={() => { void cancel(); }}>Cancel invocation</button>
      </fieldset>}
      <p className="hint">Cancellation after execution began records an uncertain outcome. An approved request still requires current group grants and a single-use execution permit.</p>
    </>}
    <button disabled={busy} onClick={() => setAttempt(value => value + 1)}>Reload approval</button>
  </section>;
}
