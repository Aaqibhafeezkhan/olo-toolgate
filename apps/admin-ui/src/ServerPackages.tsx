// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState, type FormEvent } from 'react';
import type { PackageDeployment, PackageVersion } from '@olo-labs/toolgate-contracts/v2';
import { ControlClient } from './api';
import { Failure } from './Failure';
import { Table } from './Table';

/** The values a version's tools declare: each global with its default, and each destination's host setting. */
export function declaredValues(version: PackageVersion): Record<string, unknown> {
  const values: Record<string, unknown> = {};
  for (const tool of version.manifest?.tools ?? []) {
    for (const global of tool.descriptor.variables.globals) values[global.name] = global.default ?? values[global.name] ?? '';
    for (const destination of tool.descriptor.destinations) if (!(destination.hostSetting in values)) values[destination.hostSetting] = '';
  }
  return values;
}

/** Control verifies every upload and builds what Tool Hosts run. Deploying adds each new tool disabled: it still needs a
 * reviewed enable, a Tool Group, a binding that allows the package and a grant. */
export function ServerPackages({ client }: { client: ControlClient }) {
  const [versions, setVersions] = useState<readonly PackageVersion[]>(), [deployments, setDeployments] = useState<readonly PackageDeployment[]>([]);
  const [error, setError] = useState<unknown>(), [busy, setBusy] = useState(false), [attempt, setAttempt] = useState(0);
  const [file, setFile] = useState<File>(), [selected, setSelected] = useState(''), [globals, setGlobals] = useState('{}'), [notice, setNotice] = useState('');
  useEffect(() => {
    const abort = new AbortController(); setError(undefined);
    Promise.all([client.serverPackages(abort.signal), client.serverDeployments(abort.signal)])
      .then(([v, d]) => { if (!abort.signal.aborted) { setVersions(v.items); setDeployments(d.items); } })
      .catch(e => { if (!abort.signal.aborted) setError(e); });
    return () => abort.abort();
  }, [client, attempt]);
  async function mutate(action: () => Promise<string>) {
    if (busy) return; setBusy(true); setError(undefined); setNotice('');
    try { setNotice(await action()); setAttempt(a => a + 1); } catch (e) { setError(e); } finally { setBusy(false); }
  }
  function choose(digest: string) {
    setSelected(digest);
    const version = versions?.find(v => v.packageDigest === digest);
    setGlobals(JSON.stringify(version ? declaredValues(version) : {}, null, 2));
  }
  function upload(event: FormEvent) {
    event.preventDefault(); if (!file) return;
    void mutate(async () => { const v = await client.uploadServerPackage(file); choose(v.packageDigest); return `Verified ${v.packageId} ${v.version}.`; });
  }
  function deploy(event: FormEvent) {
    event.preventDefault();
    let values: Record<string, unknown>;
    try { values = JSON.parse(globals) as Record<string, unknown>; } catch { setError(new Error('Values must be a JSON object.')); return; }
    void mutate(async () => { const d = await client.deployServerPackage({ packageDigest: selected, globals: values }); return `Deployed ${d.packageId} ${d.version} as revision ${d.revision}. Enable its tools with a reviewed change.`; });
  }
  const running = new Map(deployments.map(d => [d.packageDigest, d]));
  return <><p className="eyebrow">Tool Hosts</p><h1>Server packages</h1>
    <p className="intro">Upload a package built with <code>toolgate pack</code>, then deploy it to Tool Hosts. New tools start disabled; enable each one with a reviewed change, add it to a Tool Group and grant it.</p>
    {Boolean(error) && <Failure error={error} retry={() => setAttempt(a => a + 1)} />}
    {notice && <p role="status">{notice}</p>}
    {!versions ? <p role="status">Loading packages…</p> : versions.length === 0 ? <p>No packages uploaded yet.</p> :
      <div className="table-wrap"><Table><caption>Uploaded versions, newest first</caption><thead><tr><th scope="col">Package</th><th scope="col">Version</th><th scope="col">Tools</th><th scope="col">Digest</th><th scope="col">Deployed</th></tr></thead>
        <tbody>{versions.map(v => <tr key={v.packageDigest}><th scope="row">{v.packageId}</th><td>{v.version}</td><td>{(v.manifest?.tools ?? []).map(t => t.descriptor.toolId).join(', ')}</td><td><code title={v.packageDigest}>{v.packageDigest.slice(7, 19)}</code></td><td>{running.has(v.packageDigest) ? `Revision ${running.get(v.packageDigest)?.revision}` : 'No'}</td></tr>)}</tbody></Table></div>}
    <section className="guidance"><h2>Upload a package</h2><form onSubmit={upload}>
      <label htmlFor="server-package-file">Package file (.tgpkg)</label>
      <input id="server-package-file" type="file" accept=".tgpkg" required onChange={e => setFile(e.target.files?.[0])} />
      <button disabled={busy || !file}>Upload and verify</button></form></section>
    <section className="guidance"><h2>Deploy a version</h2><form onSubmit={deploy}>
      <label htmlFor="server-package-version">Version</label>
      <select id="server-package-version" required value={selected} onChange={e => choose(e.target.value)}><option value="">Choose a version</option>{(versions ?? []).map(v => <option key={v.packageDigest} value={v.packageDigest}>{v.packageId} {v.version}</option>)}</select>
      <label htmlFor="server-package-globals">Global and host setting values (JSON)</label>
      <textarea id="server-package-globals" required maxLength={65536} value={globals} onChange={e => setGlobals(e.target.value)} />
      <button disabled={busy || !selected}>Deploy</button></form></section>
  </>;
}
