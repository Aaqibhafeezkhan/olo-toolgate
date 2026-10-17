// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import type { ClientDownloadManifest, ClientInstallerManifest, ClientPlatform } from '@olo-labs/toolgate-contracts';

/** Installation assets are public; neither a session token nor cookies are sent. */
export function ClientDownloads() {
  const [manifest, setManifest] = useState<ClientDownloadManifest>();
  const [failed, setFailed] = useState(false);
  const [installers, setInstallers] = useState<ClientInstallerManifest>();
  const [gatewayUrl, setGatewayUrl] = useState('');
  const [gatewayFailed, setGatewayFailed] = useState(false);
  const [copyStatus, setCopyStatus] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    fetch('/api/public/v1/clients/configuration', { credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) })
      .then(async response => {
        if (!response.ok) throw new Error('Unavailable');
        const document = await response.text(); if (document.length > 4096) throw new Error('Too large');
        const value = JSON.parse(document) as { serverUrl?: unknown };
        if (typeof value.serverUrl !== 'string' || value.serverUrl.length > 2048) throw new Error('Invalid gateway');
        const url = new URL(value.serverUrl);
        if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('Invalid gateway');
        if (!controller.signal.aborted) setGatewayUrl(url.origin);
      }).catch(() => { if (!controller.signal.aborted) setGatewayFailed(true); });
    fetch('/api/public/v1/clients', { credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) })
      .then(async response => {
        if (!response.ok) throw new Error('Unavailable');
        const document = await response.text(); if (document.length > 65536) throw new Error('Too large');
        const value = JSON.parse(document) as ClientDownloadManifest;
        if (!Array.isArray(value.artifacts) || value.artifacts.length < 3 || value.artifacts.length > 6 || typeof value.version !== 'string'
          || value.artifacts.some(a => !/^[a-f0-9]{64}$/.test(a.sha256) || !/^olo-toolgate-client-[0-9A-Za-z._-]+\.(zip|tar\.gz)$/.test(a.filename))) throw new Error('Invalid release');
        if (!controller.signal.aborted) setManifest(value);
        try {
          const response = await fetch('/api/public/v1/installers', { credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) });
          if (!response.ok) return;
          const document = await response.text(); if (document.length > 65536) return;
          const setup = JSON.parse(document) as ClientInstallerManifest;
          if (setup.version !== value.version || !Array.isArray(setup.artifacts) || setup.artifacts.length !== value.artifacts.length
            || setup.artifacts.some(a => !/^[a-f0-9]{64}$/.test(a.sha256) || !/^olo-toolgate-client-[0-9A-Za-z._-]+\.(setup\.exe|dmg|run)$/.test(a.filename)
              || !value.artifacts.some(original => original.target === a.target && original.platform === a.platform))) return;
          if (!controller.signal.aborted) setInstallers(setup);
        } catch { /* Archives remain available when installers have not been published. */ }
      }).catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => controller.abort();
  }, []);
  async function copyGateway() {
    try { await navigator.clipboard.writeText(gatewayUrl); setCopyStatus('Gateway URL copied.'); }
    catch { setCopyStatus('Select the Gateway URL above and copy it.'); }
  }
  const platforms: ReadonlyArray<readonly [ClientPlatform, string]> = [['WINDOWS', 'Windows'], ['MACOS', 'macOS'], ['LINUX', 'Linux']];
  return <section className="client-downloads" aria-labelledby="client-download-heading">
    <p className="eyebrow">Endpoint client</p><h2 id="client-download-heading">Install ToolGate on your computer</h2>
    <p>Download without signing in. An administrator installs the system service; it keeps running when you lock the screen or log out. Enroll the device to use protected tools.</p>
    <div className="guidance">
      {gatewayUrl ? <><label htmlFor="installer-gateway-url">Gateway URL</label>
        <input id="installer-gateway-url" value={gatewayUrl} readOnly onFocus={event => event.currentTarget.select()} />
        <button type="button" onClick={() => void copyGateway()}>Copy Gateway URL</button></>
        : <p>{gatewayFailed ? 'Gateway URL is unavailable. Contact your administrator for the current address.' : 'Loading Gateway URL…'}</p>}
      <p>The Windows download selects this gateway automatically. Its optional Gateway URL field lets you change it; a local gateway uses <code>https://localhost:18450</code>. Setup configures local certificate trust. No credentials are required.</p>
      {copyStatus && <p role="status">{copyStatus}</p>}
    </div>
    {failed ? <p aria-live="polite">Client downloads are unavailable. Contact your administrator for a published release.</p>
      : !manifest ? <p aria-live="polite">Loading client downloads…</p> : <><p>Client version {manifest.version}</p><div className="download-grid">{platforms.map(([platform, label]) => <article key={platform}>
        <h3>{label}</h3>{installers?.artifacts.filter(a => a.platform === platform).sort((a, b) => platform === 'WINDOWS' ? Number(b.target.startsWith('x86_64')) - Number(a.target.startsWith('x86_64')) : 0).map(artifact => <div key={'installer-'+artifact.target}>
          <a href={platform === 'WINDOWS' ? `/api/public/v1/clients/setup/${artifact.target}` : `/api/public/v1/clients/olo-toolgate-client-${artifact.target}.${platform === 'MACOS' ? 'dmg' : 'run'}`} download>{`Install ${label} ${artifact.target.startsWith('aarch64') ? 'ARM64' : 'x64'}`}</a>
          <details><summary>Verify installer SHA-256</summary><code>{artifact.sha256}</code></details>
        </div>)}{manifest.artifacts.filter(a => a.platform === platform && platform !== 'WINDOWS').map(artifact => <div key={artifact.target}>
          <a href={`/api/public/v1/clients/${encodeURIComponent(artifact.filename)}`} download>{`Download ${label} ${artifact.target.startsWith('aarch64') ? 'ARM64' : 'x64'}`}</a>
          <details><summary>Verify SHA-256</summary><code>{artifact.sha256}</code></details>
        </div>)}<p>{installers ? platform === 'LINUX' ? 'Mark the .run file executable, then open it. It requests administrator permission.' : platform === 'WINDOWS' ? 'Open the single EXE and approve administrator permission. The gateway and local certificate trust are configured by setup. Existing installations offer repair/reinstall or uninstall. The OLO tray icon is green when connected and red when offline or awaiting enrollment.' : 'Open the installer, enter your HTTPS server address and approve administrator permission.' : platform === 'WINDOWS' ? 'Windows installers are unavailable. Contact your administrator for a published installer.' : 'Extract the archive and follow its installation guide.'}</p>
      </article>)}</div></>}
    {installers?.artifacts.some(a=>a.platform==='WINDOWS')&&<p className="hint">Setup starts device enrollment. You can also choose Enroll this device from the tray menu. The included Chrome extension files support the page's Connect flow after Chrome approval.</p>}
    <p className="hint">Protected operations require current Gateway authorization. If authorization is unavailable, the service blocks the operation.</p>
    {installers && <p className="hint">These development installers are unsigned. Windows SmartScreen and macOS Gatekeeper may show warnings. Signing is planned separately.</p>}
  </section>;
}
