import {EffectOutcomes,AccessSimulation} from './EffectOutcomes';
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { ControlUser } from '@olo-labs/toolgate-contracts';
import { ApiError, ControlClient } from './api';
import type { DirectoryKind, DirectoryRecords, DirectoryPages } from './operations.generated';
import { Failure } from './Failure';
import { Approvals } from './Approvals';
import {ConfigurationRequests} from './ConfigurationRequests';
import {ConfigurationTransfer} from './ConfigurationTransfer';
import {ServerConfiguration,DeviceConfiguration,SettingsTransfer} from './ServerSettings';
import { Fleet } from './Fleet';
import { Builder } from './Builder';
import { ServerPackages } from './ServerPackages';
import { Enrollment } from './Enrollment';
import { QuickstartTools } from './QuickstartTools';
import { ClientDownloads } from './ClientDownloads';
import { ClientConnection } from './ClientConnection';
import {Devices} from './Devices';
import {StandardPresets} from './StandardPresets';
import {RemoteRequests} from './RemoteRequests';
import { ThemePicker } from './Theme';
import { Audit } from './Audit';
import { DirectoryEditor, entityTitles } from './DirectoryEditor';
import {AgentToolMapping,PublishAccess,GroupMembershipEditor} from './AccessMapping';
import {Table,ActionButton,StatusToggle} from './Table';
import {protectedRecord,useRecordActions} from './RecordActions';
import {ApprovalBell} from './ApprovalBell';
import {EditorDialog} from './EditorDialog';
import {sessionPresentation} from './SessionPresentation';
import oloLogo from './assets/olo.png';

declare const __APP_VERSION__: string;
declare const __APP_BUILD__: string;
const directorySections = [['users','Users'],['teams','Teams'],['roles','Typed roles'],['tools','Tools'],['policies','Policies'],['devices','Devices'],['agents','Agents'],['agentGroups','Agent groups'],['toolGroups','Tool groups'],['deviceGroups','Device groups'],['grants','Access grants'],['delegations','Human delegations'],['agentDelegations','Agent delegations'],['bindings','Execution bindings'],['extractors','Resource extractors'],['workloadBindings','Workload identities'],['identityBindings','Human identities'],['deviceEvidence','Device evidence']] as const;
const sections = [['overview','Overview'],...directorySections,['audit','Audit log'],['approvals','Approvals'],['config-reviews','Configuration reviews'],['config-server','Server'],['config-device','Device'],['config-backup','Backup/Restore'],['outcomes','Effect outcomes'],['simulation','Access simulation'],['fleet','Packages'],['server-packages','Server packages'],['builder','Tool builder'],['enroll','Enroll device'],['local','Built-in tools and vault'],['requests','Client tool requests'],['mapping','Agent Group tool mapping'],['about','About']] as const;
const navigationGroups=[{label:'Audit',routes:['audit','approvals','config-reviews','outcomes','simulation']},{label:'Tools',routes:['tools','toolGroups','grants','bindings','policies','extractors','server-packages','builder','local','requests']},{label:'Devices',routes:['devices','deviceGroups','deviceEvidence','enroll','fleet']},{label:'Users',routes:['users','teams','roles','identityBindings','delegations']},{label:'Agents',routes:['agents','agentGroups','mapping','workloadBindings','agentDelegations']},{label:'Administration',routes:['config-server','config-device','config-backup','about']}] as const;
type Route = typeof sections[number][0];
type RecordValue = DirectoryRecords[DirectoryKind];
/** Old #configuration links open Administration > Server; links naming a review open its queue. */
function redirectLegacyHash() {
  const [path, query] = window.location.hash.split('?');
  if (path !== '#configuration') return;
  const id = new URLSearchParams(query).get('id');
  window.history.replaceState(null, '', id ? `#config-reviews?id=${encodeURIComponent(id)}` : '#config-server');
}
const routeFromHash = (): Route => { redirectLegacyHash(); return sections.find(([route]) => window.location.hash.split('?')[0] === `#${route}`)?.[0] ?? 'overview'; };
const safeOrigin = () => window.location.protocol === 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname);

/** Authenticated shell; identities/roles are verified only by Control. */
export function App() {
  const quickstart = document.querySelector('meta[name=toolgate-mode]')?.getAttribute('content') === 'quickstart';
  const [expandedGroups, setExpandedGroups] = useState<string[]>([]);
  const [presentation,setPresentation]=useState<ReturnType<typeof sessionPresentation>>();
  const [superAdmin,setSuperAdmin]=useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try { return localStorage.getItem('toolgate.sidebar.collapsed') === 'true'; } catch { return false; }
  });
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    try { const width = Number(localStorage.getItem('toolgate.sidebar.width')); return Number.isFinite(width) && width >= 190 && width <= 420 ? width : 250; } catch { return 250; }
  });
  const resizeSidebar = (width: number) => {
    const next = Math.max(190, Math.min(420, width)); setSidebarWidth(next);
    try { localStorage.setItem('toolgate.sidebar.width', String(next)); } catch { /* Preferences are optional. */ }
  };
  const toggleSidebar = () => {
    setSidebarCollapsed(!sidebarCollapsed);
    try { localStorage.setItem('toolgate.sidebar.collapsed', String(!sidebarCollapsed)); } catch { /* Preferences are optional. */ }
  };
  const [newPassword, setNewPassword] = useState('');
  const [username,setUsername]=useState('admin');
  const [passwordDisabled, setPasswordDisabled] = useState(false);
  const [client, setClient] = useState<ControlClient>();
  const [administrator,setAdministrator]=useState(false);
  const [credential, setCredential] = useState(''); const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<unknown>(); const [route, setRoute] = useState<Route>(routeFromHash);
  const active = useRef<ControlClient | undefined>(undefined);
  useEffect(() => { const update = () => setRoute(routeFromHash()); window.addEventListener('hashchange', update); return () => { window.removeEventListener('hashchange', update); active.current?.dispose(); }; }, []);
  useEffect(()=>{
    if(!client||!presentation)return;
    const save=()=>{const hash=window.location.hash;const known=sections.some(([value])=>hash.split('?')[0]===`#${value}`);try{localStorage.setItem(presentation.storageKey,JSON.stringify({hash:known&&hash.length<=256?hash:'#overview',groups:expandedGroups}));}catch{/* Browser preferences are optional. */}};
    save();window.addEventListener('pagehide',save);return()=>window.removeEventListener('pagehide',save);
  },[client,presentation,route,expandedGroups]);
  function disconnect() {
    active.current?.dispose(); active.current = undefined; setClient(undefined); window.history.replaceState(null,'',window.location.pathname+window.location.search); setRoute('overview'); setCredential(''); setConnecting(false);
    if (quickstart) void fetch('/api/quickstart/v1/logout', {method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',keepalive:true}).catch(() => {});
  }
  useEffect(() => {
    if (!quickstart || !safeOrigin()) return;
    const abort = new AbortController(); setConnecting(true);
    const status = fetch('/api/quickstart/v1/status', {credentials:'omit',redirect:'error',signal:abort.signal})
      .then(response => response.ok ? response.json() : undefined).catch(() => undefined);
    void (async () => {
      let noSession = false;
      try {
        const response = await fetch('/api/quickstart/v1/session', {credentials:'same-origin',cache:'no-store',redirect:'error',signal:abort.signal});
        if (response.status === 401) noSession = true;
        else {
          if (!response.ok) throw new ApiError(response.status, 'SESSION_FAILED');
          const body: unknown = await response.json();
          if (!body || typeof body !== 'object' || !('accessToken' in body) || typeof body.accessToken !== 'string' || !body.accessToken || body.accessToken.length > 16384) throw new ApiError(502, 'INVALID_RESPONSE');
          if (!abort.signal.aborted) await establishSession(body.accessToken);
        }
      } catch (failure) { if (!abort.signal.aborted) setError(failure); }
      const body = await status;
      if (abort.signal.aborted) return;
      setConnecting(false);
      if (body?.passwordRequired === false) {
        setPasswordDisabled(true);
        if (noSession) void connect(null, true);
      }
    })();
    return () => { abort.abort(); };
  }, [quickstart]);
  async function connect(event: FormEvent | null, automatic = false) {
    event?.preventDefault(); if (connecting || !safeOrigin()) return;
    setConnecting(true); setError(undefined);
    let accessToken = credential.trim();
    if (quickstart) {
      try {
        const response = await fetch('/api/quickstart/v1/login', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(automatic ? {} : {username,password:credential, ...(newPassword ? {newPassword} : {})}), credentials:'same-origin', redirect:'error' });
        if (!response.ok) throw new ApiError(response.status, 'LOGIN_FAILED');
        const body: unknown = await response.json();
        if (!body || typeof body !== 'object' || !('accessToken' in body) || typeof body.accessToken !== 'string' || body.accessToken.length > 16384) throw new ApiError(502, 'INVALID_RESPONSE');
        accessToken = body.accessToken; setNewPassword('');
      } catch (failure) { setCredential(''); setNewPassword(''); setError(failure); setConnecting(false); return; }
    }
    await establishSession(accessToken);
  }
  async function establishSession(accessToken: string) {
    active.current?.dispose();
    const candidate = new ControlClient(accessToken, () => { if (active.current === candidate) { disconnect(); setError(new ApiError(401, 'UNAUTHORIZED')); } });
    active.current = candidate; setCredential('');
    try {
      let isAdministrator=true,isSuper=false;
      try { const session=await candidate.adminSession(); isSuper=session.role==='SUPER_ADMIN'; }
      catch (failure) {
        if (!(failure instanceof ApiError) || failure.status !== 403) throw failure;
        isAdministrator=false;
        await candidate.approvals();
      }
      if (active.current === candidate) {
        const display=sessionPresentation(accessToken);let hash=window.location.hash,groups:string[]=[];
        try{const saved=JSON.parse(localStorage.getItem(display.storageKey)??'null');if(!hash&&typeof saved?.hash==='string'&&saved.hash.length<=256&&sections.some(([value])=>saved.hash.split('?')[0]===`#${value}`))hash=saved.hash;if(Array.isArray(saved?.groups))groups=saved.groups.filter((value:unknown)=>navigationGroups.some(group=>group.label===value));}catch{/* Ignore invalid or unavailable browser preferences. */}
        if(!hash||!isAdministrator&&!['#overview','#approvals','#about'].includes(hash.split('?')[0]))hash='#overview';
        window.history.replaceState(null,'',hash);setRoute(routeFromHash());setExpandedGroups(groups);setPresentation(display);setAdministrator(isAdministrator);setSuperAdmin(isSuper);setClient(candidate);
        if(display.userId)void candidate.user(display.userId).then(user=>{if(active.current===candidate)setPresentation({...display,name:user.name});}).catch(()=>{});
      }
    }
    catch (failure) { if (active.current === candidate) { candidate.dispose(); active.current = undefined; setError(failure); } }
    finally { if (!active.current || active.current === candidate) setConnecting(false); }
  }
  function navigationGroup(group:{label:string;routes:readonly Route[]}) {
    return sidebarCollapsed?<button className="rail-menu" key={group.label} title={group.label} aria-label={group.label} onClick={()=>{setExpandedGroups([group.label]);toggleSidebar();}}><MenuIcon name={group.label}/></button>:
      <details className="nav-group" key={group.label} open={expandedGroups.includes(group.label)} onToggle={event=>{const opened=event.currentTarget.open;setExpandedGroups(previous=>opened===previous.includes(group.label)?previous:opened?[...previous,group.label]:previous.filter(label=>label!==group.label));}}><summary><MenuIcon name={group.label}/>{group.label}</summary><div className="nav-submenu">{group.routes.filter(value=>quickstart||value!=='local').map(value=><a key={value} href={`#${value}`} aria-current={route===value?'page':undefined}><MenuIcon name={value}/>{sections.find(([key])=>key===value)![1]}</a>)}</div></details>;
  }
  return <><a className="skip" href="#main" onClick={event => { event.preventDefault(); document.getElementById('main')?.focus(); }}>Skip to content</a>
    {!client ? <main id="main" tabIndex={-1} className="connect-page"><header className="login-header"><div className="brand"><img className="brand-logo" src={oloLogo} alt="OLO" /> <span>ToolGate</span></div>
      <ThemePicker /></header><div className="connect-card"><p className="eyebrow">Organization console</p><h1>Manage your workspace</h1>
        {quickstart ? <p role="status" className="notice">Quickstart · Single node · Non-HA. First login requires a new strong password.</p> : <p>Connect to Control with an access token issued by your organization’s identity provider.</p>}
        {!safeOrigin() && <div role="alert" className="notice error">Open this console over HTTPS before connecting.</div>}
        {Boolean(error) && <Failure error={error} />}
        {passwordDisabled&&<><p role="status">Local password-free Quickstart. Reviewers still use their independent passwords.</p><button onClick={() => { void connect(null, true); }} disabled={connecting}>Reconnect administrator</button></>}
        <form onSubmit={connect}>{quickstart&&<><label htmlFor="local-username">Local account</label><select id="local-username" value={username} onChange={e=>setUsername(e.target.value)} disabled={connecting}><option value="admin">Administrator</option><option value="reviewer-1">Reviewer 1</option><option value="reviewer-2">Reviewer 2</option><option value="test-readonly">ReadOnly test user</option><option value="test-readwrite">ReadAndWrite test user</option><option value="test-admin">Admin test user</option></select></>}<label htmlFor="access-token">{quickstart ? 'Password' : 'Access token'}</label>
          <input id="access-token" type="password" autoComplete="off" spellCheck={false} maxLength={16384} required value={credential} onChange={e => setCredential(e.target.value)} disabled={connecting || !safeOrigin()} aria-describedby="token-help" />
          {quickstart && <><label htmlFor="new-password">New password (required on first login)</label><input id="new-password" type="password" autoComplete="new-password" minLength={16} maxLength={128} value={newPassword} onChange={e => setNewPassword(e.target.value)} disabled={connecting} /></>}
          <p id="token-help" className="hint">{quickstart ? 'Your session survives refresh until it expires or you disconnect. Your password is never saved in the browser.' : 'Kept in memory for this session. Refreshing or disconnecting clears it.'}</p>
          <button className="primary" disabled={connecting || !safeOrigin()}>{connecting ? 'Connecting…' : 'Connect to workspace'}</button>
          {connecting && <p role="status">Verifying your session with Control…</p>}
        </form></div><ClientDownloads /><footer>ToolGate {__APP_VERSION__} · Organization administration</footer></main>
    : <div className="app-frame"><header className="topbar app-topbar"><div className="brand"><img className="brand-logo" src={oloLogo} alt="OLO" /><span>ToolGate</span></div><span className="portal-label">{administrator?'Admin Portal':'Approval workspace'}</span><div className="topbar-actions"><span className="tag">{quickstart ? 'Quickstart · Non-HA' : 'Organization workspace'}</span><ThemePicker /></div></header>
      <div className={`shell${sidebarCollapsed ? ' navigation-collapsed' : ''}`}><aside id="sidebar" className={`sidebar${sidebarCollapsed ? ' sidebar-collapsed' : ''}`} style={{width:sidebarCollapsed ? 64 : sidebarWidth}}>
      {!sidebarCollapsed && <p className="sidebar-caption">{administrator?'Workspace':'Approval workspace'}</p>}
      <nav aria-label="Main navigation"><div className="navigation-scroll"><a href="#overview" title="Overview" aria-label={sidebarCollapsed?'Overview':undefined} aria-current={route==='overview'?'page':undefined}><MenuIcon name="overview"/>{!sidebarCollapsed&&'Overview'}</a>
        {administrator?navigationGroups.slice(0,-1).map(navigationGroup):<a href="#approvals" title="Approvals" aria-label={sidebarCollapsed?'Approvals':undefined} aria-current={route==='approvals'?'page':undefined}><MenuIcon name="approvals"/>{!sidebarCollapsed&&'Approvals'}</a>}
      </div>{navigationGroup(administrator?navigationGroups[navigationGroups.length-1]:{label:'Administration',routes:['about']})}</nav>
      <div className="sidebar-bottom"><div className="sidebar-user">{!sidebarCollapsed&&<span className="signed-in-user" title={presentation?.name}>{presentation?.name}</span>}<ApprovalBell client={client} administrator={administrator} superAdmin={superAdmin} userId={presentation?.userId}/></div>{!sidebarCollapsed&&<><span className="connection">Connected to Control</span><small>v{__APP_VERSION__}</small></>}<button title="Disconnect" aria-label="Disconnect" onClick={disconnect}>{sidebarCollapsed?<MenuIcon name="disconnect"/>:'Disconnect'}</button></div></aside>
      <div className="navigation-controls" role="region" aria-label="Navigation controls"><button className="sidebar-toggle" style={{left:sidebarCollapsed?18:sidebarWidth-38}} aria-label={sidebarCollapsed?'Expand navigation':'Collapse navigation'} aria-controls="sidebar" aria-expanded={!sidebarCollapsed} onClick={toggleSidebar}>{sidebarCollapsed?'>':'<'}</button>
      {!sidebarCollapsed && <div className="sidebar-resizer" role="separator" aria-label="Resize navigation" aria-orientation="vertical" aria-valuemin={190} aria-valuemax={420} aria-valuenow={sidebarWidth} tabIndex={0}
        onPointerDown={event => { event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId); }}
        onPointerMove={event => { if (event.currentTarget.hasPointerCapture(event.pointerId)) resizeSidebar(event.clientX); }}
        onPointerUp={event => { if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }}
        onKeyDown={event => { if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) { event.preventDefault(); resizeSidebar(event.key==='Home'?190:event.key==='End'?420:sidebarWidth+(event.key==='ArrowLeft'?-20:20)); } }} />}</div>

      <div className="workspace">
        <main id="main" tabIndex={-1} className={administrator&&route==='requests'?'request-canvas':undefined}>{route==='about'?<About/>:!administrator?route==='overview'?<><p className="eyebrow">Workspace overview</p><h1>Your workspace, at a glance</h1><p>Open your approval queue to review pending requests.</p><a href="#approvals">Open approvals</a></>:<Approvals client={client}/>:route==='outcomes'?<EffectOutcomes client={client}/>:route==='simulation'?<AccessSimulation client={client}/>:route==='config-reviews'?<ConfigurationRequests client={client} superAdmin={superAdmin}/>:route==='config-server'?<ServerConfiguration client={client}/>:route==='config-device'?<DeviceConfiguration client={client}/>:route==='config-backup'?<><div className="page-heading"><h1>Backup/Restore</h1></div><ConfigurationTransfer client={client}/><SettingsTransfer client={client}/></>:route==='mapping'?<AgentToolMapping client={client}/>:route==='devices'?<Devices client={client}/>:route==='requests'?<RemoteRequests client={client}/>:route === 'local' ? quickstart ? <QuickstartTools client={client} /> : <p>Local tools are available in Quickstart.</p> : route === 'overview' ? <Dashboard client={client} /> : route === 'audit' ? <Audit client={client}/> : route === 'approvals' ? <Approvals client={client} /> : route === 'fleet' ? <Fleet client={client} /> : route === 'server-packages' ? <ServerPackages client={client} /> : route === 'builder' ? <Builder client={client} /> : route === 'enroll' ? <><Enrollment client={client} /><ClientDownloads /></> : <Directory key={route} client={client} kind={route} />}</main>
        <footer>Control verifies permissions. Gateway checks current policy for every runtime authorization.</footer></div></div></div>}
  </>;
}

const iconPaths: Record<string,string> = {
  about:'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 11v6 M12 7.5v.5',
  overview:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  Audit:'M6 3h12v18H6z M9 7h6 M9 11h6 M9 15h4',
  Tools:'M14 4a5 5 0 0 0-6 6L3 17l4 4 7-7a5 5 0 0 0 6-6l-4 3-3-3z',
  Devices:'M3 4h18v12H3z M8 21h8 M12 16v5',
  Users:'M8 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M2 21v-3a6 6 0 0 1 12 0v3 M17 4a3 3 0 0 1 0 6 M18 14a4 4 0 0 1 4 4v3',
  Agents:'M7 7h10v12H7z M12 3v4 M9 11h1 M14 11h1 M10 15h4 M3 10h4 M17 10h4',
  users:'M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10 M3 22v-2a9 9 0 0 1 18 0v2',
  teams:'M4 3h6v6H4z M14 3h6v6h-6z M9 15h6v6H9z M7 9v3h10V9 M12 12v3',
  roles:'M12 2l8 4v6c0 5-8 10-8 10S4 17 4 12V6z M8 12l3 3 5-6',
  agents:'M5 7h14v13H5z M12 2v5 M8 11h1 M15 11h1 M9 16h6 M2 11h3 M19 11h3',
  tools:'M5 4h14v16H5z M8 8l3 3-3 3 M13 15h3',
  policies:'M12 3l9 5-9 5-9-5z M3 12l9 5 9-5 M3 16l9 5 9-5',
  devices:'M7 2h10v20H7z M10 18h4',
  enroll:'M4 5h10v14H4z M17 8v8 M13 12h8',
  fleet:'M3 7l9-5 9 5v10l-9 5-9-5z M3 7l9 5 9-5 M12 12v10',
  'server-packages':'M3 4h18v6H3z M3 14h18v6H3z M7 7h1 M7 17h1',
  audit:'M3 3h14v18H3z M6 7h7 M6 11h7 M6 15h4 M17 13l5 5 M18 18a4 4 0 1 0 0-8 4 4 0 0 0 0 8',
  approvals:'M4 4h16v16H4z M7 12l3 3 7-7',
  builder:'M8 5l-6 7 6 7 M16 5l6 7-6 7 M14 3l-4 18',
  local:'M4 3h16v6H4z M4 15h16v6H4z M7 6h1 M7 18h1 M12 9v6',
  requests:'M3 6h14 M13 2l4 4-4 4 M21 18H7 M11 14l-4 4 4 4',
  mapping:'M4 4h6v6H4z M14 14h6v6h-6z M10 7h7v7',
  deviceGroups:'M3 4h7v6H3z M14 4h7v6h-7z M8 15h8v6H8z M6 10v2h12v-2 M12 12v3',
  disconnect:'M10 3H3v18h7 M9 12h13 M17 7l5 5-5 5',
  Administration:'M4 6h10 M18 6h2 M4 12h4 M12 12h8 M4 18h12 M20 18h0 M16 4v4 M10 10v4 M18 16v4',
  'config-reviews':'M4 4h12v16H4z M7 8h6 M7 12h6 M15 15l5 5 M17 17a3 3 0 1 0 0-6 3 3 0 0 0 0 6',
  'config-server':'M4 4h16v6H4z M4 14h16v6H4z M8 7h1 M8 17h1',
  'config-device':'M7 2h10v20H7z M10 18h4 M10 6h4',
  'config-backup':'M12 3v12 M7 10l5 5 5-5 M4 17v4h16v-4',
};
function MenuIcon({name}:{name:string}) { return <svg className="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={iconPaths[name]}/></svg>; }

function About() {
  return <section aria-labelledby="about-heading"><div className="page-heading"><h1 id="about-heading">About ToolGate</h1></div>
    <dl><dt>Version</dt><dd>{__APP_VERSION__}</dd><dt>Build</dt><dd>{__APP_BUILD__}</dd></dl>
    <p>OLO Labs · Licensed under Apache-2.0</p></section>;
}

function Dashboard({ client }: { client: ControlClient }) {
  const [counts,setCounts] = useState<Partial<Record<DirectoryKind,{ count: number; more: boolean }>>>({});
  const [error,setError] = useState<unknown>(); const [attempt,setAttempt] = useState(0);
  useEffect(() => {
    const abort = new AbortController(); setCounts({}); setError(undefined);
    Promise.all(directorySections.map(async ([kind]) => { const page = await client.list(kind, undefined, abort.signal); return [kind,{ count:page.items.length, more:!!page.nextCursor }] as const; }))
      .then(values => { if (!abort.signal.aborted) setCounts(Object.fromEntries(values)); })
      .catch(failure => { if (!abort.signal.aborted) setError(failure); });
    return () => abort.abort();
  },[client,attempt]);
  return <><p className="eyebrow">Workspace overview</p><h1>Your organization, at a glance</h1><p className="intro">A clear place to manage the people and capabilities in your directory.</p>
    {error ? <Failure error={error} retry={() => setAttempt(attempt+1)} /> : Object.keys(counts).length === 0 ? <p role="status">Loading your directory…</p> :
      <div className="stats">{directorySections.map(([kind,label]) => <a className="stat" key={kind} href={`#${kind}`}><span>{label}</span><strong>{counts[kind]?.count}{counts[kind]?.more ? '+' : ''}</strong><small>View directory →</small></a>)}</div>}
    <section className="guidance"><span className="tag">Getting organized</span><h2>Start with the people who use your tools.</h2><p>Add users, then browse teams and registered capabilities. Policies describe stored configuration; runtime distribution is a separate step.</p><a href="#users" className="text-link">Open users →</a></section>
    <p className="hint">Counts show the first page (up to 50 records). A + means more pages are available. Open Clients to see current connection status.</p><RemoteRequests client={client} compact/></>;
}

function Directory({ client, kind }: { client: ControlClient; kind: DirectoryKind }) {
  const label = sections.find(([value]) => value === kind)![1];
  const [page,setPage] = useState<DirectoryPages[DirectoryKind]>();
  const [cursor,setCursor] = useState<string>(); const [history,setHistory] = useState<(string|undefined)[]>([]);
  const [error,setError] = useState<unknown>(); const [attempt,setAttempt] = useState(0);
  const [selected,setSelected] = useState<RecordValue>(); const [creating,setCreating] = useState(false);
  const actions=useRecordActions(client,kind,()=>setAttempt(value=>value+1));
  useEffect(() => {
    const abort = new AbortController(); setPage(undefined); setError(undefined); setSelected(undefined); setCreating(false);
    let pending=false;
    async function poll() {
      if(pending)return;pending=true;
      try {const result=await client.list(kind,cursor,abort.signal);if(!abort.signal.aborted){setPage(result);setError(undefined);}}
      catch(failure){if(!abort.signal.aborted)setError(failure);}
      finally {pending=false;}
    }
    void poll();const timer=kind==='devices'?setInterval(()=>void poll(),2000):undefined;
    return () => {abort.abort();clearInterval(timer);};
  },[client,kind,cursor,attempt]);
  const refresh = () => setAttempt(attempt+1);
  return <><div className="page-heading"><div><p className="eyebrow">Organization directory</p><h1>{label}</h1></div><div className="actions"><button onClick={refresh}>Refresh</button>{<button className="primary" onClick={() => { setSelected(undefined); setCreating(true); }}>Add {kind==='users'?'user':kind==='devices'?'client':entityTitles[kind]}</button>}</div></div>
    <p className="intro">{kind === 'users' ? 'Users inherit access through Teams. Verified new identities appear disabled for administrator review.' : kind === 'policies' ? 'Policies constrain complete grants through current online authority. Snapshot publication and device adoption are separate states.' : kind === 'devices' ? 'Manage registered clients. Directory activation and device enrollment are separate approvals.' : `Browse registered ${label.toLowerCase()} in your organization.`}</p>
    {kind==='devices'&&<p className="hint">Green means an active client checked in within the last two minutes. Red means the client is offline or unavailable; the status label explains why. The client list and connection status refresh every two seconds.</p>}
    {error ? <Failure error={error} retry={refresh} /> : !page ? <p role="status">Loading {label.toLowerCase()}…</p> : <>
      {page.items.length === 0 ? <section className="empty"><h2>No {label.toLowerCase()} on this page</h2><p>{kind === 'users' ? 'Add a directory user to get started.' : 'Records will appear here when they are added to Control.'}</p></section> :
        <div className="table-wrap"><Table disabled={actions.busy} bulkActions={keys=>{const records=page.items.filter(record=>keys.includes(record.id)),mutable=records.filter(record=>!protectedRecord(kind,record.id,client.bootstrapAdminUserId));return <><button disabled={actions.busy||!records.some(record=>!record.enabled)} onClick={()=>void actions.run(records,'enable')}>Enable selected</button><button disabled={actions.busy||!mutable.some(record=>record.enabled)} onClick={()=>void actions.run(mutable,'disable')}>Disable selected</button><button className="danger" disabled={actions.busy||!mutable.length} onClick={()=>actions.requestDelete(mutable)}>Delete selected</button></>;}}><caption className="sr-only">{label} directory</caption><thead><tr><th scope="col">Name</th><th scope="col">Identifier</th>{kind==='devices'&&<th scope="col">Connection</th>}<th scope="col">Directory status</th><th scope="col">Revision</th><th scope="col">Actions</th></tr></thead><tbody>{page.items.map(record => <tr key={record.id}><th scope="row">{record.name}</th><td><code>{record.id}</code></td>{kind==='devices'&&<td><ClientConnection client={client} id={record.id} enabled={record.enabled} ownerUserId={'ownerUserId' in record?record.ownerUserId:''}/></td>}<td data-sort-value={record.enabled?'Enabled':'Disabled'}><StatusToggle enabled={record.enabled} label={record.id} disabled={actions.busy||protectedRecord(kind,record.id,client.bootstrapAdminUserId)} onClick={()=>void actions.run([record],record.enabled?'disable':'enable')}/></td><td data-sort-value={record.revision}>{record.revision}</td><td><div className="actions"><ActionButton icon="edit" label={`Edit ${record.id}`} disabled={actions.busy} onClick={()=>{setSelected(record);setCreating(false);}}/><ActionButton icon="delete" label={`Delete ${record.id}`} title={protectedRecord(kind,record.id,client.bootstrapAdminUserId)?'The setup administrator and required default groups cannot be deleted':`Delete ${record.id}; fresh onboarding will be required`} disabled={actions.busy||protectedRecord(kind,record.id,client.bootstrapAdminUserId)} onClick={()=>actions.requestDelete([record])}/></div></td></tr>)}</tbody></Table></div>}
      <div className="pagination"><span>{page.items.length} records on this page</span><div className="actions"><button disabled={!history.length} onClick={() => { setCursor(history.at(-1)); setHistory(history.slice(0,-1)); }}>Previous page</button><button disabled={!page.nextCursor} onClick={() => { setHistory([...history,cursor]); setCursor(page.nextCursor); }}>Next page</button></div></div>
    </>}
    {actions.feedback}
    {['teams','agentGroups','toolGroups','deviceGroups'].includes(kind)&&<StandardPresets client={client}/>}
    {kind==='deviceGroups'&&<><p className="hint">Device groups contain devices. Complete group grants and Tool Group execution bindings determine access. Disabled groups grant no access.</p><PublishAccess client={client}/></>}
    {(creating || selected) && (kind === 'users' ? <UserEditor key={selected?.id ?? 'new'} client={client} user={selected as ControlUser | undefined} close={() => { setCreating(false); setSelected(undefined); }} saved={refresh} /> : <DirectoryEditor key={selected?.id??'new'} client={client} kind={kind} record={selected as DirectoryRecords[Exclude<DirectoryKind,'users'>] | undefined} close={()=>{setCreating(false);setSelected(undefined);}} saved={refresh}/>)}
  </>;
}

function UserEditor({ client, user, close, saved }: { client: ControlClient; user?: ControlUser; close: () => void; saved: () => void }) {
  const [id,setId] = useState(user?.id ?? ''); const [name,setName] = useState(user?.name ?? ''); const [enabled,setEnabled] = useState(user?.enabled ?? false);
  const [email,setEmail] = useState(user?.email ?? '');
  const protectedAdmin = !!user && user.id === client.bootstrapAdminUserId;
  const [current,setCurrent] = useState(user); const [busy,setBusy] = useState(false); const [error,setError] = useState<unknown>(); const [confirm,setConfirm] = useState(false);
  const alive = useRef(true);
  const pending = useRef<{ body: string; key: string } | undefined>(undefined);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; },[]);
  const mutationKey = (body: string) => { if (pending.current?.body !== body) pending.current = {body,key:crypto.randomUUID()}; return pending.current!.key; };
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return; setBusy(true); setError(undefined);
    const record: ControlUser = {id,name,enabled,revision:current?.revision ?? 1,...(email?{email}:{})};
    try { await client.saveUser(record,!!current,mutationKey(JSON.stringify(record))); if (alive.current) saved(); }
    catch (failure) { if (alive.current) setError(failure); } finally { if (alive.current) setBusy(false); }
  }
  async function reload() {
    if (!current || busy) return; setBusy(true); setError(undefined);
    try { const record = await client.user(current.id); if (alive.current) { setCurrent(record); setName(record.name); setEnabled(record.enabled); setEmail(record.email??''); pending.current = undefined; } }
    catch (failure) { if (alive.current) setError(failure); } finally { if (alive.current) setBusy(false); }
  }
  async function remove() {
    if (!current || busy) return; setBusy(true); setError(undefined);
    try { await client.deleteUser(current,mutationKey(`delete:${current.id}:${current.revision}`)); if (alive.current) saved(); }
    catch (failure) { if (alive.current) setError(failure); } finally { if (alive.current) setBusy(false); }
  }
  return <EditorDialog title={current ? 'Edit user' : 'Add directory user'} close={close} busy={busy}><section aria-label="User editor">
    {Boolean(error) && <Failure error={error} />}
    <form onSubmit={submit} className="user-form"><label htmlFor="user-id">Identifier</label><input id="user-id" value={id} required maxLength={128} disabled={!!current || busy} onChange={e => setId(e.target.value)} />
      <label htmlFor="user-name">Display name</label><input id="user-name" value={name} required maxLength={200} disabled={busy} onChange={e => setName(e.target.value)} />
      <label htmlFor="user-email">Email</label><input id="user-email" type="email" value={email} maxLength={254} disabled={busy} onChange={e => setEmail(e.target.value)} />
      <label className="checkbox"><input type="checkbox" checked={enabled} disabled={busy||protectedAdmin} onChange={e => setEnabled(e.target.checked)} />Enabled in directory</label>
      {protectedAdmin&&<p className="hint">The setup administrator cannot be deleted or disabled. Authority comes from current Team and Role assignments.</p>}
      <p className="hint">New users start disabled and join the default Team. Enable after review, then manage their Team membership. Individuals cannot receive Roles or permissions directly. The server validates all values and references. {current && `Editing revision ${current.revision}.`}</p><div className="actions"><button className="primary" disabled={busy}>{busy ? 'Working…' : 'Save user'}</button>{current && <button type="button" disabled={busy} onClick={reload}>Reload current record</button>}</div>
    </form>
    {current&&<GroupMembershipEditor client={client} entity="users" id={current.id} saved={saved}/>}
    {current && !protectedAdmin && <div className="delete-area">{confirm ? <><p>Delete this directory record? Its identifier cannot be reused. Referenced users cannot be deleted.</p><button disabled={busy} className="danger" onClick={remove}>Confirm delete</button> <button disabled={busy} onClick={() => setConfirm(false)}>Cancel delete</button></> : <button className="danger" disabled={busy} onClick={() => setConfirm(true)}>Delete user</button>}</div>}
  </section></EditorDialog>;
}
