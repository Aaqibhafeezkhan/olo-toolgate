// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table} from './Table';
import { useEffect, useState } from 'react';
import type { ControlAuditPage } from '@olo-labs/toolgate-contracts';
import { ControlClient } from './api';
import { Failure } from './Failure';

/** Bounded append-only administrative metadata; never displays mutation payloads. */
export function Audit({client}:{client:ControlClient}) {
  const [page,setPage]=useState<ControlAuditPage>();
  const [cursor,setCursor]=useState('0'),[attempt,setAttempt]=useState(0),[error,setError]=useState<unknown>();
  useEffect(()=>{const abort=new AbortController();setPage(undefined);setError(undefined);
    client.audit(cursor,abort.signal).then(value=>{if(!abort.signal.aborted)setPage(value);}).catch(failure=>{if(!abort.signal.aborted)setError(failure);});return()=>abort.abort();
  },[client,cursor,attempt]);
  return <><div className="page-heading"><h1>Audit log</h1><button onClick={()=>setAttempt(attempt+1)}>Refresh</button></div>
    <p className="intro">Administrative changes, actor identifiers and request correlations. Tool authorization history remains in Gateway audit output.</p>
    {error!==undefined?<Failure error={error} retry={()=>setAttempt(attempt+1)}/>:!page?<p role="status">Loading audit…</p>:<>
      {!page.items.length?<p role="status">No audit events on this page.</p>:<div className="table-wrap"><Table><caption className="sr-only">Administrative audit events</caption><thead><tr><th>Time</th><th>Action</th><th>Target</th><th>Actor</th><th>Request</th></tr></thead><tbody>{page.items.map(event=><tr key={event.sequence}><td data-sort-value={Date.parse(event.occurredAt)}>{event.occurredAt}</td><td>{event.operation}</td><td>{event.target}</td><td><code>{event.actorId}</code></td><td>{event.requestId}</td></tr>)}</tbody></Table></div>}
      <div className="actions"><button disabled={cursor==='0'} onClick={()=>setCursor('0')}>First page</button><button disabled={!page.nextCursor} onClick={()=>setCursor(page.nextCursor!)}>Next page</button></div>
    </>}
  </>;
}
