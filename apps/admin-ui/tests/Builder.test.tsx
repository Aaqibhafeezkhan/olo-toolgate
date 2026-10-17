// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {Builder} from '../src/Builder';
import {ControlClient} from '../src/api';
import axe from 'axe-core';
import fixtures from '../../../tests/fixtures/contracts/v1/valid.json';

afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks();});
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status});
const group={id:'default-tools',name:'Default tools',canBuild:true};
const status={sdkVersion:JSON.parse(fixtures.SdkBuilderSave.source).sdkVersion,configured:true,maxSourceCharacters:32768,groups:[group]};
function client(build=true){const client=new ControlClient('test-token',vi.fn());vi.spyOn(client,'can').mockImplementation(permission=>permission==='tool_builder.read'||build&&permission==='tool_builder.build');return client;}
const empty=(url:string)=>url.endsWith('/status')?json(status):json({items:[]});

it('shows an accessible canonical JSON SDK editor',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>empty(url)));render(<Builder client={client()}/>);
 await screen.findByText('No SDK projects yet. Add a project to start with Hello World.');
 fireEvent.click(screen.getByRole('button',{name:'Add project'}));
 const project=JSON.parse((screen.getByLabelText('JSON SDK project') as HTMLTextAreaElement).value);
 expect(project).toMatchObject({schemaVersion:1,tools:[{kind:'SERVER',inputs:{text:{default:'World'}}}]});
 expect(screen.queryByLabelText('Code editor')).toBeNull();
 expect((await axe.run(screen.getByRole('dialog'),{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});
it('saves SDK source and builds the exact saved revision without an inline execution request',async()=>{
 const fetcher=vi.fn(async(url:string,init?:RequestInit)=>{
  if(init?.method==='POST'){
   const body=JSON.parse(String(init.body));
   if(url.endsWith('/drafts'))return json({...fixtures.SdkBuilderDraft,id:body.id,source:body.source,revision:1});
   return json({...fixtures.SdkBuilderBuild,id:body.id,draftId:body.draftId});
  }
  return empty(url);
 });
 vi.stubGlobal('fetch',fetcher);render(<Builder client={client()}/>);
 await screen.findByText('No SDK projects yet. Add a project to start with Hello World.');fireEvent.click(screen.getByRole('button',{name:'Add project'}));fireEvent.click(screen.getByRole('button',{name:'Save project'}));
 await screen.findByText('Project saved at revision 1.');fireEvent.click(screen.getByRole('button',{name:'Build package'}));
 await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>url.endsWith('/builds')&&init?.method==='POST')).toBe(true));
 const call=fetcher.mock.calls.find(([url,init])=>url.endsWith('/builds')&&init?.method==='POST')!;
 expect(JSON.parse(String(call[1]?.body))).toEqual({id:expect.any(String),draftId:expect.any(String),expectedRevision:1});
 expect(fetcher.mock.calls.some(([url])=>url.includes('/tests')||url.includes('/publication'))).toBe(false);
});
it('allows authorized read-only artifact downloads and keeps credentials out of URLs',async()=>{
 const build={...fixtures.SdkBuilderBuild,state:'READY',artifactDigest:`sha256:${'a'.repeat(64)}`,sourceArchiveDigest:`sha256:${'b'.repeat(64)}`};
 const fetcher=vi.fn(async(url:string)=>url.endsWith('/package')?new Response(new Uint8Array([80,75]),{headers:{'Content-Type':'application/vnd.ololabs.toolgate.package+zip'}}):url.endsWith('/status')?json(status):json({items:url.includes('/builds')?[build]:[]}));
 vi.stubGlobal('fetch',fetcher);vi.stubGlobal('URL',class extends URL {static createObjectURL=vi.fn(()=> 'blob:test');static revokeObjectURL=vi.fn();});vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
 render(<Builder client={client(false)}/>);
 expect((await screen.findByRole('button',{name:'Add project'}) as HTMLButtonElement).disabled).toBe(true);
 fireEvent.click(await screen.findByRole('button',{name:`Download package ${build.id}`}));
 await waitFor(()=>expect(URL.createObjectURL).toHaveBeenCalled());
 expect(screen.getByRole('button',{name:`Download source ${build.id}`})).toBeTruthy();
 expect(fetcher.mock.calls.map(call=>call[0]).every(url=>!url.includes('test-token'))).toBe(true);
});
it('does not reflect source-bearing backend errors',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({code:'VALIDATION',message:'private-source-detail'},400)));render(<Builder client={client()}/>);
 await screen.findByRole('alert');expect(document.body.textContent).not.toContain('private-source-detail');expect(screen.getByRole('button',{name:'Try again'})).toBeTruthy();
});
