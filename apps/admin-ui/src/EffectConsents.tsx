// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useEffect, useRef, useState} from 'react';
import type {EnterpriseInvocationPage} from '@olo-labs/toolgate-contracts';
import type {EffectConsentView} from '@olo-labs/toolgate-contracts/v2';
import {ApiError, ControlClient} from './api';
import {Failure} from './Failure';

export function EffectConsents({client}: {client: ControlClient}) {
  const [page, setPage] = useState<EnterpriseInvocationPage>();
  const [cursor, setCursor] = useState<string>();
  const [history, setHistory] = useState<(string | undefined)[]>([]);
  const [selected, setSelected] = useState('');
  const [view, setView] = useState<EffectConsentView>();
  const [error, setError] = useState<unknown>();
  const [absent, setAbsent] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const locked = useRef(false);
  const selectedRef = useRef(selected); selectedRef.current = selected;
  useEffect(() => {
    const abort = new AbortController();
    client.invocations(cursor, abort.signal).then(value => {if (!abort.signal.aborted) setPage(value);})
      .catch(failure => {if (!abort.signal.aborted) setError(failure);});
    return () => abort.abort();
  }, [client, cursor, refresh]);
  useEffect(() => {
    setView(undefined); setConfirmed(false); setError(undefined); setAbsent(false);
    if (!selected) return;
    const abort = new AbortController();
    client.effectConsent(selected, abort.signal).then(value => {if (!abort.signal.aborted) setView(value);})
      .catch(failure => {if (!abort.signal.aborted) {
        if (failure instanceof ApiError && failure.status === 404) setAbsent(true); else setError(failure);
      }});
    return () => abort.abort();
  }, [client, selected, refresh]);
  async function decide(revoke: boolean) {
    if (!view || !confirmed || locked.current) return;
    const id = selected; locked.current = true; setBusy(true); setError(undefined);
    try {
      const body = {previewDigest: view.previewDigest, expectedRevision: view.revision};
      const next = await (revoke ? client.revokeEffectConsent(id, body) : client.approveEffectConsent(id, body));
      if (selectedRef.current === id) {setView(next); setConfirmed(false); setRefresh(value => value + 1);}
      window.dispatchEvent(new Event('toolgate:approval-change'));
    } catch (failure) {
      if (selectedRef.current === id) {setError(failure); setConfirmed(false); if (failure instanceof ApiError && failure.status === 409) setRefresh(value => value + 1);}
    } finally {locked.current = false; setBusy(false);}
  }
  const expired = view && view.preview.expiresAtUnixMs <= Date.now();
  return <section aria-label="Exact effect consent">
    <h2>Exact effect consent</h2>
    <p>Review the actual mapped outbound values and permitted effect count. This consent is required in addition to any policy approvals. The displayed counts limit distinct effects. These unfenced upstreams may finish work after a timeout; reconcile uncertain outcomes before another attempt.</p>
    <label>Operation to review<select value={selected} disabled={busy} onChange={event => setSelected(event.target.value)}>
      <option value="">Choose an operation</option>
      {page?.items.map(item => <option key={item.id} value={item.id}>{item.id} · {item.evaluation.toolId} · {item.state}</option>)}
    </select></label>
    <div className="pagination"><button disabled={busy || !history.length} onClick={() => {setCursor(history.at(-1)); setHistory(history.slice(0, -1));}}>Previous consent operations</button>
      <button disabled={busy || !page?.nextCursor} onClick={() => {setHistory([...history, cursor]); setCursor(page?.nextCursor);}}>Next consent operations</button>
      <button disabled={busy} onClick={() => setRefresh(value => value + 1)}>Refresh effect consent</button></div>
    {Boolean(error) && <Failure error={error}/>}
    {absent && <p>This operation has no exact-effect preview. Review any policy approvals separately.</p>}
    {selected && !view && !absent && !error && <p role="status">Loading effect preview…</p>}
    {view && <>
      <p><strong>{view.state}{expired ? ' · Expired' : ''}</strong> · Revision {view.revision}</p>
      <dl><dt>Tool and action</dt><dd>{view.preview.toolId} · {view.preview.action}</dd>
        <dt>Operation and execution scope</dt><dd>{view.preview.operationId} · {view.preview.runId}</dd>
        <dt>Complete arguments digest</dt><dd><code>{view.preview.argumentsDigest}</code></dd>
        <dt>Valid until</dt><dd>{new Date(view.preview.expiresAtUnixMs).toLocaleString()}</dd>
        <dt>Resources</dt><dd><pre>{JSON.stringify(view.preview.resources, null, 2)}</pre></dd></dl>
      {view.preview.slots.map(slot => <section key={slot.slotIndex} aria-label={'Effect slot '+slot.slotIndex}>
        <h3>{slot.request.classification} · {slot.request.routeId} · {slot.permittedCount} permitted effect{slot.permittedCount === 1 ? '' : 's'}</h3>
        <p>{slot.request.method} {slot.request.scheme}://{slot.request.host}:{slot.request.port}{slot.request.path}{slot.request.query ? '?'+slot.request.query : ''}</p>
        <pre>{JSON.stringify({headers: slot.request.headers, contentType: slot.request.contentType, bodyMode: slot.request.bodyMode, body: slot.request.body}, null, 2)}</pre>
      </section>)}
      <label><input type="checkbox" checked={confirmed} disabled={busy || expired || view.state === 'REVOKED'} onChange={event => setConfirmed(event.target.checked)}/>I reviewed these outbound values, resources and effect counts.</label>
      <button disabled={busy || !confirmed || expired || view.state !== 'PENDING'} onClick={() => void decide(false)}>Approve exact effects</button>
      <button disabled={busy || !confirmed || view.state === 'REVOKED'} onClick={() => void decide(true)}>Revoke effect consent</button>
    </>}
  </section>;
}
