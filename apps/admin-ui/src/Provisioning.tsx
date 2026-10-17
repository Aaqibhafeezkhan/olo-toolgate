// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import type { ProfileChange, ProvisioningPackageStatus, ProvisioningStatus } from '@olo-labs/toolgate-contracts/v2';
import { ControlClient } from './api';
import { Failure } from './Failure';
import { Table } from './Table';

/** Where a profile package's exports go: its Tool Hosts and device groups. */
export function targets(status: ProvisioningPackageStatus): string {
  const parts = [...(status.hostIds?.length ? [`Tool Hosts ${status.hostIds.join(', ')}`] : []),
    ...(status.deviceGroupIds?.length ? [`device groups ${status.deviceGroupIds.join(', ')}`] : [])];
  return parts.length ? parts.join('; ') : 'None';
}

/** One recorded difference from the profile applied before, in words. */
export function describeChange(change: ProfileChange): string {
  if (change.change === 'ADDED') return `Added ${change.packageId} ${change.version}`;
  if (change.change === 'REMOVED') return `Removed ${change.packageId} ${change.previousVersion} (still installed)`;
  return change.previousVersion === change.version
    ? `Changed ${change.packageId} ${change.version}: digest ${change.previousPackageDigest?.slice(7, 19)} to ${change.packageDigest?.slice(7, 19)}`
    : `Changed ${change.packageId} ${change.previousVersion} to ${change.version}`;
}

/** The latest recorded status of every deployment profile Control applied, read-only. Profiles come from deployment
 * configuration; reapplying a changed profile records its package changes here. */
export function Provisioning({ client, refresh }: { client: ControlClient; refresh: number }) {
  const [profiles, setProfiles] = useState<readonly ProvisioningStatus[]>(), [error, setError] = useState<unknown>(), [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const abort = new AbortController(); setError(undefined);
    client.provisioningStatuses(abort.signal).then(page => { if (!abort.signal.aborted) setProfiles(page.items); })
      .catch(e => { if (!abort.signal.aborted) setError(e); });
    return () => abort.abort();
  }, [client, refresh, attempt]);
  return <section className="guidance"><h2>Deployment profiles</h2>
    {Boolean(error) && <Failure error={error} retry={() => setAttempt(a => a + 1)} />}
    {!profiles ? !error && <p role="status">Loading deployment profiles…</p> : profiles.length === 0 ? <p>No deployment profile applied.</p> :
      profiles.map(p => <div key={p.profileId}>
        <h3>{p.profileId}: {p.state === 'READY' ? 'Ready' : p.state === 'PENDING' ? 'Pending' : 'Failed'}</h3>
        <p>Generation {p.generation}, applied {new Date(p.appliedAtUnixMs).toISOString()}, profile digest <code title={p.profileDigest}>{p.profileDigest.slice(7, 19)}</code>.</p>
        <div className="table-wrap"><Table><caption>Packages of {p.profileId}</caption><thead><tr><th scope="col">Package</th><th scope="col">Version</th><th scope="col">State</th><th scope="col">Step</th><th scope="col">Targets</th><th scope="col">Reason</th></tr></thead>
          <tbody>{p.packages.map(s => <tr key={s.packageId}><th scope="row">{s.packageId}{s.required ? '' : ' (optional)'}</th><td>{s.version}</td><td>{s.state}</td><td>{s.step}</td><td>{targets(s)}</td><td>{s.reason ?? ''}</td></tr>)}</tbody></Table></div>
        {p.changes?.length ? <><h4>Changes from the previous profile</h4><ul>{p.changes.map(c => <li key={c.packageId}>{describeChange(c)}</li>)}</ul></> : null}
      </div>)}
  </section>;
}
