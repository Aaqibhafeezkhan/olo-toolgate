// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState, type FormEvent } from 'react';
import { ControlClient, type MarketplaceItem, type MarketplacePublisher, type MarketplaceDetail } from './api';
import { Failure } from './Failure';
import { usePermissions } from './Permissions';

/** Marketplace design §8.1: the text an administrator confirms before Control imports a community package. */
export const IMPORT_CONFIRMATION = 'Community package, signed by the Marketplace after structural validation. The signature proves where it came from, not that it is safe. Review its settings and permissions before deploying.';

/** Marketplace images load through Control's media proxy, so the console keeps img-src 'self'. Anything that is not a
 * Marketplace media id is not shown. */
export function mediaUrl(id: unknown): string | undefined {
  return typeof id === 'string' && /^m_[0-9a-f]{32}$/.test(id) ? `/api/control/v1/marketplace/media/${id}` : undefined;
}

/** Only https links leave the console; the Marketplace's display data is not trusted to choose a scheme. */
export function safeLink(url: unknown): string | undefined {
  if (typeof url !== 'string') return undefined;
  try { const parsed = new URL(url); return parsed.protocol === 'https:' && !parsed.username && !parsed.password ? parsed.href : undefined; } catch { return undefined; }
}

function Compatibility({ item }: { item: MarketplaceItem }) {
  if (!item.control) return null;
  return item.control.compatible ? <span className="badge">Compatible</span>
    : <span className="badge warning" title={item.control.reasons.join('; ')}>Not compatible: {item.control.reasons[0]}</span>;
}

function Publisher({ publisher }: { publisher: MarketplacePublisher }) {
  const avatar = mediaUrl(publisher.avatarMediaId), profile = safeLink(publisher.profileUrl);
  const links = [['Website', publisher.websiteUrl], ['GitHub', publisher.githubUrl], ['LinkedIn', publisher.linkedinUrl]]
    .map(([label, url]) => [label, safeLink(url)] as const).filter((l): l is readonly [string, string] => Boolean(l[1]));
  return <section className="guidance" aria-labelledby="marketplace-publisher"><h3 id="marketplace-publisher">{publisher.displayName}</h3>
    {avatar && <img src={avatar} alt="" width={48} height={48} />}
    {publisher.organization && <p>{publisher.organization}</p>}
    {publisher.bio && <p>{publisher.bio}</p>}
    {publisher.country && <p>Country: {publisher.country}</p>}
    {publisher.publicContactEmail && <p>Contact: {publisher.publicContactEmail}</p>}
    {links.length > 0 && <ul>{links.map(([label, url]) => <li key={label}><a href={url} target="_blank" rel="noopener noreferrer">{label}</a></li>)}</ul>}
    {profile && <p><a href={profile} target="_blank" rel="noopener noreferrer">View public profile</a></p>}
  </section>;
}

function Detail({ detail, onPublisher }: { detail: MarketplaceDetail; onPublisher: () => void }) {
  const repository = safeLink(detail.repositoryUrl), documentation = safeLink(detail.documentationUrl);
  return <section className="guidance" aria-labelledby="marketplace-detail"><h3 id="marketplace-detail">{detail.title} {detail.version}</h3>
    <p className="pre-wrap">{detail.description}</p>
    {(detail.screenshots ?? []).map(id => mediaUrl(id)).filter(Boolean).map(src => <img key={src} src={src} alt={`${detail.title} screenshot`} className="screenshot" />)}
    <dl>
      <dt>Package</dt><dd><code>{detail.packageId}</code> {detail.version}</dd>
      <dt>SHA-256</dt><dd><code>{detail.archiveSha256}</code></dd>
      <dt>Package digest</dt><dd><code>{detail.packageDigest}</code></dd>
      {detail.license && <><dt>License</dt><dd>{detail.license}</dd></>}
      <dt>Publisher</dt><dd><button type="button" className="link" onClick={onPublisher}>{detail.publisher.displayName}</button></dd>
    </dl>
    {(repository || documentation) && <ul>
      {repository && <li><a href={repository} target="_blank" rel="noopener noreferrer">Repository</a></li>}
      {documentation && <li><a href={documentation} target="_blank" rel="noopener noreferrer">Documentation</a></li>}</ul>}
    <h4>Tools</h4><ul>{(detail.tools ?? []).map(t => <li key={t.name}><strong>{t.title || t.name}</strong> (<code>{t.name}</code>, {t.effect}): {t.description}</li>)}</ul>
    <h4>Required settings</h4>{(detail.requiredSettings ?? []).length === 0 ? <p>None.</p> :
      <ul>{detail.requiredSettings!.map(s => <li key={s.kind + s.name}><code>{s.name}</code> ({s.kind}{s.type ? `, ${s.type}` : ''}){s.default != null ? `, default ${s.default}` : ''}{s.description ? `: ${s.description}` : ''}</li>)}</ul>}
  </section>;
}

/** Browse the OLO Orgs Marketplace and import a version as an uploaded server package. Control fetches, verifies and
 * stores it; deploying, enabling and granting stay with the rest of this page. Hidden when the Marketplace is off. */
export function Marketplace({ client, onImported }: { client: ControlClient; onImported: () => void }) {
  const {can}=usePermissions(client),canImport=can('package.import');
  const [enabled, setEnabled] = useState<boolean>(), [items, setItems] = useState<readonly MarketplaceItem[]>(), [query, setQuery] = useState(''), [search, setSearch] = useState('');
  const [error, setError] = useState<unknown>(), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [attempt, setAttempt] = useState(0);
  const [detail, setDetail] = useState<MarketplaceDetail>(), [publisher, setPublisher] = useState<MarketplacePublisher>(), [confirm, setConfirm] = useState<{ packageId: string; version: string; title: string }>();
  useEffect(() => {
    const abort = new AbortController(); setError(undefined);
    client.marketplaceStatus(abort.signal).then(async status => {
      if (abort.signal.aborted) return; setEnabled(status.enabled === true); if (status.enabled !== true) return;
      const page = await client.marketplaceCatalog(search, abort.signal); if (!abort.signal.aborted) setItems(page.items);
    }).catch(e => { if (!abort.signal.aborted) setError(e); });
    return () => abort.abort();
  }, [client, search, attempt]);
  async function act(action: () => Promise<void>) {
    if (busy) return; setBusy(true); setError(undefined); setNotice('');
    try { await action(); } catch (e) { setError(e); } finally { setBusy(false); }
  }
  const show = (item: MarketplaceItem) => act(async () => { setPublisher(undefined); setDetail(await client.marketplaceDetail(item.packageId, item.version)); });
  const showPublisher = (slug: string) => act(async () => setPublisher(await client.marketplacePublisher(slug)));
  const install = (item: MarketplaceItem) => act(async () => {
    if (!canImport) return;
    const versions = await client.marketplaceVersions(item.packageId);
    if (!versions.installVersion) { setNotice(`No listed version of ${item.title} is compatible with this ToolGate.`); return; }
    setConfirm({ packageId: item.packageId, version: versions.installVersion, title: item.title });
  });
  const confirmImport = () => act(async () => {
    if (!confirm || !canImport) return;
    const imported = await client.importMarketplacePackage({ packageId: confirm.packageId, version: confirm.version });
    setConfirm(undefined); setNotice(`Imported ${imported.packageId} ${imported.version}. It is listed with the uploaded versions; deploy it from there.`); onImported();
  });
  function submit(event: FormEvent) { event.preventDefault(); setSearch(query.trim()); }
  if (enabled === false) return null;
  return <section className="guidance" aria-labelledby="marketplace-heading"><h2 id="marketplace-heading">Marketplace</h2>
    <p>Community tool packages from the OLO Orgs Marketplace. Control checks each release's signature, digests and compatibility before it stores the package; you then deploy, enable and grant it here as with an upload.</p>
    {Boolean(error) && <Failure error={error} retry={() => setAttempt(a => a + 1)} />}
    {notice && <p role="status">{notice}</p>}
    {confirm && <div className="notice" role="alertdialog" aria-labelledby="marketplace-confirm-title" aria-describedby="marketplace-confirm-text">
      <strong id="marketplace-confirm-title">Import {confirm.title} {confirm.version}?</strong>
      <p id="marketplace-confirm-text">{IMPORT_CONFIRMATION}</p>
      <button disabled={busy || !canImport} onClick={confirmImport}>Import</button> <button type="button" disabled={busy} onClick={() => setConfirm(undefined)}>Cancel</button></div>}
    <form onSubmit={submit} role="search"><label htmlFor="marketplace-search">Search the Marketplace</label>
      <input id="marketplace-search" type="search" maxLength={200} value={query} onChange={e => setQuery(e.target.value)} />
      <button disabled={busy}>Search</button></form>
    {!items ? (error ? null : <p role="status">Loading the Marketplace…</p>) : items.length === 0 ? <p>No Marketplace packages match.</p> :
      <ul className="cards">{items.map(item => { const icon = mediaUrl(item.iconMediaId), avatar = mediaUrl(item.publisher?.avatarMediaId); return <li key={item.packageId} className="card">
        {icon && <img src={icon} alt="" width={40} height={40} />}
        <h3>{item.title}</h3><p>{item.summary}</p>
        <p>{item.version} · {item.runtime} · {item.category}</p>
        <p>{avatar && <img src={avatar} alt="" width={20} height={20} />} {item.publisher?.displayName}</p>
        <Compatibility item={item} />
        <button type="button" disabled={busy} onClick={() => show(item)} aria-label={`Details for ${item.title}`}>Details</button>
        <button type="button" disabled={busy || !canImport} onClick={() => install(item)} aria-label={`Install ${item.title}`}>Install</button>
      </li>; })}</ul>}
    {detail && <Detail detail={detail} onPublisher={() => showPublisher(detail.publisher.slug)} />}
    {publisher && <Publisher publisher={publisher} />}
  </section>;
}
