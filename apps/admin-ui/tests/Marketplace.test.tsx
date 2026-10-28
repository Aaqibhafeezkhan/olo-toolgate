// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {beforeEach,afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {Marketplace,IMPORT_CONFIRMATION,mediaUrl,safeLink} from '../src/Marketplace';
import {ControlClient} from '../src/api';
import axe from 'axe-core';
beforeEach(()=>{vi.spyOn(ControlClient.prototype,'can').mockReturnValue(true);});
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks();});
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status});
const icon='m_'+'1'.repeat(32),avatar='m_'+'2'.repeat(32),shot='m_'+'3'.repeat(32);
const publisher={slug:'examples',displayName:'ToolGate Examples',organization:'OLO Labs',avatarMediaId:avatar,profileUrl:'https://marketplace.example.com/publishers/examples'};
const item={packageId:'examples.notify',version:'0.1.0',title:'Webhook notify',summary:'Posts a message.',category:'Operations',runtime:'java',iconMediaId:icon,publisher,control:{compatible:true,reasons:[]}};
const old={...item,packageId:'examples.old',title:'Old tool',iconMediaId:'https://tracker.example.com/pixel.png',control:{compatible:false,reasons:['tool protocol 1-1 does not overlap 2-2']}};
const detail={...item,description:'Sends **one** message.',repositoryUrl:'https://github.com/olo-labs/examples',documentationUrl:'javascript:alert(1)',license:'Apache-2.0',screenshots:[shot],
 tools:[{name:'notify.send',title:'Send',description:'Sends it.',effect:'NON_IDEMPOTENT'}],requiredSettings:[{kind:'destination',name:'WebhookHost',type:'STRING',default:null,description:'Where to post'}],
 packageDigest:'sha256:'+'a'.repeat(64),archiveSha256:'b'.repeat(64)};
const imported={packageId:'examples.notify',version:'0.1.0',packageDigest:'sha256:'+'a'.repeat(64),descriptorSetDigest:'sha256:'+'c'.repeat(64),state:'VERIFIED',revision:1,uploadedAtUnixMs:1};
function marketplace(overrides:Record<string,()=>Response>={}){
 return vi.fn(async(url:string,init?:RequestInit)=>{
  const path=url.split('?')[0];
  if(overrides[path])return overrides[path]();
  if(path==='/api/control/v1/marketplace')return json({enabled:true,marketplaceId:'olo-orgs',baseUrl:'https://marketplace.example.com',keys:[]});
  if(path==='/api/control/v1/marketplace/catalog')return json({page:1,limit:50,total:2,items:[item,old]});
  if(path==='/api/control/v1/marketplace/packages/examples.notify')return json({packageId:'examples.notify',latestVersion:'0.2.0',installVersion:'0.1.0',versions:[]});
  if(path==='/api/control/v1/marketplace/packages/examples.old')return json({packageId:'examples.old',latestVersion:'0.1.0',installVersion:null,versions:[]});
  if(path==='/api/control/v1/marketplace/packages/examples.notify/0.1.0')return json(detail);
  if(path==='/api/control/v1/marketplace/publishers/examples')return json({...publisher,bio:'Examples.',websiteUrl:'http://plain.example.com',githubUrl:'https://github.com/olo-labs',publicContactEmail:'hello@example.com',packages:[]});
  if(path==='/api/control/v1/marketplace/imports'&&init?.method==='POST')return json(imported,201);
  return json({code:'NOT_FOUND'},404);
 });
}

it('shows nothing when the Marketplace is off',async()=>{
 const fetcher=vi.fn(async()=>json({enabled:false,keys:[]}));vi.stubGlobal('fetch',fetcher);
 const {container}=render(<Marketplace client={new ControlClient('token',vi.fn())} onImported={vi.fn()}/>);
 await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(1));
 await waitFor(()=>expect(container.innerHTML).toBe(''));
});
it('allows read-only catalog details while disabling imports',async()=>{
 vi.mocked(ControlClient.prototype.can).mockImplementation(permission=>permission==='package.read');
 const fetcher=marketplace();vi.stubGlobal('fetch',fetcher);
 render(<Marketplace client={new ControlClient('token',vi.fn())} onImported={vi.fn()}/>);
 const install=await screen.findByRole('button',{name:'Install Webhook notify'});
 expect((install as HTMLButtonElement).disabled).toBe(true);
 fireEvent.click(screen.getByRole('button',{name:'Details for Webhook notify'}));
 await screen.findByText('Sends **one** message.');
 expect(fetcher.mock.calls.some(([,init])=>init?.method==='POST')).toBe(false);
});

it('lists the catalog with images only through the media proxy and Control\'s verdict',async()=>{
 const fetcher=marketplace();vi.stubGlobal('fetch',fetcher);
 const {container}=render(<Marketplace client={new ControlClient('token',vi.fn())} onImported={vi.fn()}/>);
 await screen.findByText('Webhook notify');
 const sources=[...container.querySelectorAll('img')].map(i=>i.getAttribute('src'));
 expect(sources).toContain(`/api/control/v1/marketplace/media/${icon}`);
 expect(sources.every(s=>s?.startsWith('/api/control/v1/marketplace/media/m_'))).toBe(true);
 expect(screen.getByText('Compatible')).toBeTruthy();
 expect(screen.getByText(/Not compatible: tool protocol 1-1/)).toBeTruthy();
 expect((await axe.run(container,{rules:{"color-contrast":{enabled:false}}})).violations).toEqual([]);
 fireEvent.change(screen.getByLabelText('Search the Marketplace'),{target:{value:' notify '}});
 fireEvent.click(screen.getByRole('button',{name:'Search'}));
 await waitFor(()=>expect(fetcher.mock.calls.some(([url])=>String(url).includes('/catalog?')&&new URLSearchParams(String(url).split('?')[1]).get('q')==='notify')).toBe(true));
});

it('shows a version, its publisher and only https links',async()=>{
 vi.stubGlobal('fetch',marketplace());render(<Marketplace client={new ControlClient('token',vi.fn())} onImported={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:'Details for Webhook notify'}));
 await screen.findByText('Sends **one** message.');
 expect(screen.getByText('b'.repeat(64))).toBeTruthy();
 expect(screen.getByRole('link',{name:'Repository'}).getAttribute('href')).toBe('https://github.com/olo-labs/examples');
 expect(screen.queryByRole('link',{name:'Documentation'})).toBeNull();
 expect(screen.getByAltText('Webhook notify screenshot').getAttribute('src')).toBe(`/api/control/v1/marketplace/media/${shot}`);
 expect(screen.getByText(/WebhookHost/).closest('li')?.textContent).toContain('Where to post');
 fireEvent.click(screen.getByRole('button',{name:'ToolGate Examples'}));
 await screen.findByText('Examples.');
 expect(screen.getByRole('link',{name:'View public profile'}).getAttribute('href')).toBe('https://marketplace.example.com/publishers/examples');
 expect(screen.getByRole('link',{name:'GitHub'})).toBeTruthy();
 expect(screen.queryByRole('link',{name:'Website'})).toBeNull();
 expect(screen.getByText('Contact: hello@example.com')).toBeTruthy();
});

it('imports the newest compatible version after the confirmation',async()=>{
 const fetcher=marketplace(),onImported=vi.fn();vi.stubGlobal('fetch',fetcher);
 render(<Marketplace client={new ControlClient('token',vi.fn())} onImported={onImported}/>);
 fireEvent.click(await screen.findByRole('button',{name:'Install Webhook notify'}));
 const dialog=await screen.findByRole('alertdialog');
 expect(dialog.textContent).toContain(IMPORT_CONFIRMATION);
 expect(dialog.textContent).toContain('Import Webhook notify 0.1.0?');
 expect(fetcher.mock.calls.some(([url])=>String(url).endsWith('/imports'))).toBe(false);
 fireEvent.click(screen.getByRole('button',{name:'Import'}));
 await screen.findByText(/Imported examples.notify 0.1.0/);
 const post=fetcher.mock.calls.find(([url])=>String(url).endsWith('/imports'))!;
 expect(JSON.parse(String(post[1]?.body))).toEqual({packageId:'examples.notify',version:'0.1.0'});
 expect(onImported).toHaveBeenCalledTimes(1);
 expect(screen.queryByRole('alertdialog')).toBeNull();
});

it('says when no version is compatible and when Control refuses an import',async()=>{
 vi.stubGlobal('fetch',marketplace({'/api/control/v1/marketplace/imports':()=>json({code:'VALIDATION',requestId:'req-1'},422)}));
 render(<Marketplace client={new ControlClient('token',vi.fn())} onImported={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:'Install Old tool'}));
 await screen.findByText('No listed version of Old tool is compatible with this ToolGate.');
 expect(screen.queryByRole('alertdialog')).toBeNull();
 fireEvent.click(screen.getByRole('button',{name:'Install Webhook notify'}));
 fireEvent.click(await screen.findByRole('button',{name:'Import'}));
 await screen.findByText('Control refused this package: it failed a verification or compatibility check.');
 expect(screen.getByText('Reference: req-1')).toBeTruthy();
 fireEvent.click(screen.getByRole('button',{name:'Cancel'}));
 expect(screen.queryByRole('alertdialog')).toBeNull();
});

it('accepts only Marketplace media ids and https links',()=>{
 expect(mediaUrl('m_'+'f'.repeat(32))).toBe('/api/control/v1/marketplace/media/m_'+'f'.repeat(32));
 for(const id of ['m_'+'F'.repeat(32),'../x','https://example.com/a.png',null,7])expect(mediaUrl(id)).toBeUndefined();
 expect(safeLink('https://example.com/a')).toBe('https://example.com/a');
 for(const url of ['http://example.com','javascript:alert(1)','https://user:pass@example.com/','not a url',null])expect(safeLink(url)).toBeUndefined();
});
