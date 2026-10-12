// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {ServerPackages} from '../src/ServerPackages';
import {ControlClient} from '../src/api';
import axe from 'axe-core';
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status});
const digest='sha256:'+'b'.repeat(64);
const tool={descriptor:{toolId:'acme.orders/greet',variables:{globals:[{name:'Greeting',type:'STRING',required:true,sensitive:false,default:'Hello'}],secrets:[],claims:[]},destinations:[{id:'items-api',hostSetting:'ItemsApiHost',port:443,spkiPins:[]}]}};
const version={packageId:'acme.orders',version:'0.1.0',packageDigest:digest,descriptorSetDigest:'sha256:'+'a'.repeat(64),state:'VERIFIED',revision:1,uploadedAtUnixMs:1,manifest:{tools:[tool]}};
it('shows the empty state accessibly',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({items:[]})));const {container}=render(<ServerPackages client={new ControlClient('token',vi.fn())}/>);
 await screen.findByText('No packages uploaded yet.');
 expect((await axe.run(container,{rules:{"color-contrast":{enabled:false}}})).violations).toEqual([]);
});
it('uploads the exact bytes and deploys with the declared values',async()=>{
 const fetcher=vi.fn(async(url:string,init?:RequestInit)=>{
  if(url.endsWith('/packages/uploads'))return json(version,201);
  if(init?.method==='POST')return json({packageId:'acme.orders',version:'0.1.0',packageDigest:digest,revision:1,serviceProfileDigest:digest,serviceProfileRevision:1,globalsDigest:digest,toolIds:['acme.orders/greet'],deployedAtUnixMs:1});
  return json({items:url.endsWith('/deployments')?[]:[version]});
 });
 vi.stubGlobal('fetch',fetcher);render(<ServerPackages client={new ControlClient('token',vi.fn())}/>);
 await screen.findByText('acme.orders/greet');
 const file=new File([new Uint8Array([80,75,5,6])],'orders.tgpkg');
 fireEvent.change(screen.getByLabelText('Package file (.tgpkg)'),{target:{files:[file]}});
 // jsdom cannot give a file input a value, so submit the form directly.
 fireEvent.submit(screen.getByRole('button',{name:'Upload and verify'}).closest('form')!);
 await screen.findByText('Verified acme.orders 0.1.0.');
 const upload=fetcher.mock.calls.find(([url])=>url.endsWith('/packages/uploads'))!;
 expect(upload[1]?.body).toBe(file);expect(new Headers(upload[1]?.headers).get('Content-Type')).toBe('application/vnd.ololabs.toolgate.package+zip');
 expect(JSON.parse((screen.getByLabelText(/Global and host setting values/) as HTMLTextAreaElement).value)).toEqual({Greeting:'Hello',ItemsApiHost:''});
 fireEvent.change(screen.getByLabelText(/Global and host setting values/),{target:{value:'{"Greeting":"Hi","ItemsApiHost":"items.example.com"}'}});
 fireEvent.click(screen.getByRole('button',{name:'Deploy'}));
 await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>url.endsWith('/packages/deployments')&&init?.method==='POST'&&JSON.parse(String(init.body)).globals.ItemsApiHost==='items.example.com')).toBe(true));
 await screen.findByText(/Deployed acme.orders 0.1.0 as revision 1/);
});
