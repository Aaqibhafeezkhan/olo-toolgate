// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {Builder} from '../src/Builder';
import {ControlClient} from '../src/api';
import axe from 'axe-core';
import fixtures from '../../../tests/fixtures/contracts/v1/valid.json';
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status});
it('has an accessible editor and empty state',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({items:[]})));render(<Builder client={new ControlClient('token',vi.fn())}/>);
 await screen.findByText('No drafts yet. Add a tool to get started.');
 expect(screen.queryByLabelText('Code editor')).toBeNull();fireEvent.click(screen.getByRole('button',{name:'Add tool'}));
 expect(screen.getByLabelText('Code editor')).toBeTruthy();expect((await axe.run(screen.getByRole('dialog'),{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});
it('locks a sealed definition and allows a new version',async()=>{
 const draft={...fixtures.BuilderDraft,sealed:true,revision:3};vi.stubGlobal('fetch',vi.fn(async(url:string)=>json({items:url.includes('/drafts')?[draft]:[]})));
 render(<Builder client={new ControlClient('token',vi.fn())}/>);fireEvent.click(await screen.findByRole('button',{name:`Edit draft ${draft.id}`}));
 expect((screen.getByLabelText('Code editor').closest('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
 fireEvent.click(screen.getByRole('button',{name:'Clone to new version'}));expect((screen.getByLabelText('Version') as HTMLInputElement).value).toBe('');
 expect((screen.getByLabelText('Code editor').closest('fieldset') as HTMLFieldSetElement).disabled).toBe(false);
});
it('routes tests to the chosen client with the authoritative revision',async()=>{
 const draft=fixtures.BuilderDraft;const fetcher=vi.fn(async(url:string,init?:RequestInit)=>init?.method==='POST'?json(fixtures.BuilderTestRecord):json({items:url.includes('/drafts')?[draft]:[]}));vi.stubGlobal('fetch',fetcher);
 render(<Builder client={new ControlClient('token',vi.fn())}/>);fireEvent.click(await screen.findByRole('button',{name:`Edit draft ${draft.id}`}));
 fireEvent.change(screen.getByLabelText('Designated enrolled client IDs, comma separated'),{target:{value:'designated-device'}});fireEvent.click(screen.getByRole('button',{name:'Test on designated client'}));
 await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>url.endsWith('/tests')&&init?.method==='POST'&&JSON.parse(String(init.body)).deviceId==='designated-device'&&JSON.parse(String(init.body)).expectedRevision===1)).toBe(true));
});
it('redacts secret-bearing backend errors and offers retry',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({code:'VALIDATION',message:'private-source-detail'},400)));render(<Builder client={new ControlClient('token',vi.fn())}/>);
 await screen.findByRole('alert');expect(document.body.textContent).not.toContain('private-source-detail');expect(screen.getByRole('button',{name:'Try again'})).toBeTruthy();
});
it('publishes and deploys the sealed package through authoritative APIs',async()=>{
 const draft={...fixtures.BuilderDraft,sealed:true,revision:3};
 const fetcher=vi.fn(async(url:string,init?:RequestInit)=>init?.method==='POST'?json(url.endsWith('/release')?fixtures.FleetPackageRelease:fixtures.FleetRolloutRecord):json({items:url.includes('/drafts')?[draft]:[]}));vi.stubGlobal('fetch',fetcher);
 render(<Builder client={new ControlClient('token',vi.fn())}/>);fireEvent.click(await screen.findByRole('button',{name:`Edit draft ${draft.id}`}));
 fireEvent.change(screen.getByLabelText('Signed release envelope JSON'),{target:{value:JSON.stringify(fixtures.FleetPackageRelease)}});fireEvent.click(screen.getByRole('button',{name:'Publish organization release'}));await screen.findByText('Exact sealed package release verified and published.');
 fireEvent.change(screen.getByLabelText('Designated enrolled client IDs, comma separated'),{target:{value:'device-a,device-b'}});fireEvent.click(screen.getByRole('button',{name:'Deploy package'}));await screen.findByText('Deployment created. Observe desired/reported state in Packages.');
 const deployment=fetcher.mock.calls.find(([url,init])=>url.endsWith('/deploy')&&init?.method==='POST')!;
 expect(JSON.parse(String(deployment[1]?.body))).toMatchObject({packageId:'custom-echo',version:'1.0.0',deviceIds:['device-a','device-b'],percentage:10});
});
