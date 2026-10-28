// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import axe from 'axe-core';
import {McpServers,diff,localName,MAX_APPROVED_TOOLS} from '../src/McpServers';
import {ControlClient} from '../src/api';
import type {McpApproval,McpSnapshot} from '@olo-labs/toolgate-contracts/v2';

beforeEach(()=>{vi.spyOn(ControlClient.prototype,'can').mockReturnValue(true);});
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks();});
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status});
const D=(c:string)=>'sha256:'+c.repeat(64);
const PIN='sha256/'+'A'.repeat(43)+'=';
const definition=(name:string,digest:string,description=`${name} tool`)=>({tool:{name,description,inputSchema:{type:'object'}},definitionDigest:digest});
const snapshot=(tools:ReturnType<typeof definition>[]):McpSnapshot=>({revision:2,scanId:'scan-2',serverInfo:{name:'orders-mcp',version:'1.4.0'},protocolVersion:'2025-11-25',spkiPin:PIN,scannedAtUnixMs:1,tools});
const approval:McpApproval={sequence:1,snapshotRevision:1,tools:[{toolId:'mcp.orders/get-order',upstreamName:'get_order',definitionDigest:D('a')},{toolId:'mcp.orders/old',upstreamName:'old_tool',definitionDigest:D('c')}],spkiPins:[PIN],packageId:'mcp.orders',packageVersion:'1.0.1',packageDigest:D('d'),approvedAtUnixMs:1};
const server={slug:'orders',url:'https://mcp.example.com/mcp',auth:{secretName:'orders/mcp-token',scheme:'BEARER'},bindingId:'mcp-binding',strict:false,state:'APPROVED',revision:3,createdAtUnixMs:1,latestSnapshotRevision:2,approvalSequence:1,verification:{status:'CURRENT',approvalSequence:1,checkedAtUnixMs:1}};

/** Control as the page sees it: one approved server, its approvals and latest snapshot, and recorded mutations. */
function control(tools=[definition('get_order',D('b')),definition('list_orders',D('e'))],servers:unknown[]=[server]){
 const fetcher=vi.fn(async(url:string,init?:RequestInit)=>{
  if(init?.method==='POST'&&url.endsWith('/approvals'))return json({...approval,sequence:2,packageVersion:'1.0.2',tools:JSON.parse(String(init.body)).tools.map((t:{name:string;upstreamName:string})=>({toolId:`mcp.orders/${t.name}`,upstreamName:t.upstreamName,definitionDigest:D('b')}))});
  if(init?.method==='POST'&&url.endsWith('/mcp-servers'))return json({...server,slug:JSON.parse(String(init.body)).slug,state:'REGISTERED'});
  if(init?.method==='POST'&&url.endsWith('/scans'))return json({scanId:'scan-3',slug:'orders',purpose:'DISCOVER',state:'QUEUED',hostId:'host-a',createdAtUnixMs:1,deadlineUnixMs:2});
  if(url.endsWith('/approvals'))return json({items:[approval]});
  if(url.includes('/snapshots/'))return json(snapshot(tools));
  const detail=/\/mcp-servers\/([a-z][a-z0-9-]+)$/.exec(url);if(detail)return json((servers as {slug:string}[]).find(s=>s.slug===detail[1])??{...server,slug:detail[1]});
  return json({items:servers});
 });
 vi.stubGlobal('fetch',fetcher);return fetcher;
}
const review=async()=>{fireEvent.click(await screen.findByRole('button',{name:'Review orders'}));await screen.findByRole('heading',{name:'orders'});};

it('compares a snapshot with the approval in force',()=>{
 const rows=diff(snapshot([definition('get_order',D('b')),definition('list_orders',D('e')),definition('same',D('f'))]),{...approval,tools:[...approval.tools,{toolId:'mcp.orders/same',upstreamName:'same',definitionDigest:D('f')}]});
 expect(rows.map(r=>[r.name,r.change])).toEqual([['get_order','changed'],['list_orders','new'],['same','unchanged'],['old_tool','removed']]);
 expect(diff(snapshot([definition('get_order',D('b'))])).map(r=>r.change)).toEqual(['new']);
 expect(localName('Get Order!')).toBe('get-order');expect(localName('_9x')).toBe('x');
});

it('shows the empty and loaded states accessibly',async()=>{
 control(undefined,[]);const {container}=render(<McpServers client={new ControlClient('token',vi.fn())}/>);
 await screen.findByText('No MCP servers registered yet.');
 expect((await axe.run(container,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
 cleanup();control();const loaded=render(<McpServers client={new ControlClient('token',vi.fn())}/>);await review();
 await screen.findByText('changed');
 expect((await axe.run(loaded.container,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});

it('reviews definitions with the diff and approves the selected tools with their names',async()=>{
 const fetcher=control();render(<McpServers client={new ControlClient('token',vi.fn())}/>);await review();
 // The current approval's tools start selected under their approved names; a removed tool cannot be selected.
 expect((screen.getByLabelText('Approve get_order') as HTMLInputElement).checked).toBe(true);
 expect((screen.getByLabelText('Approve old_tool') as HTMLInputElement).disabled).toBe(true);
 expect((screen.getByLabelText('Local name for get_order') as HTMLInputElement).value).toBe('get-order');
 fireEvent.click(screen.getByLabelText('Approve list_orders'));
 expect((screen.getByLabelText('Local name for list_orders') as HTMLInputElement).value).toBe('list_orders');
 fireEvent.click(screen.getByRole('button',{name:'Approve 3 tools'}));
 await screen.findByText(/Approved 3 tools of orders as mcp.orders 1.0.2/);
 const [,init]=fetcher.mock.calls.find(([url,i])=>url.endsWith('/approvals')&&i?.method==='POST')!;
 expect(JSON.parse(String(init!.body))).toEqual({snapshotRevision:2,tools:[{upstreamName:'get_order',name:'get-order'},{upstreamName:'old_tool',name:'old'},{upstreamName:'list_orders',name:'list_orders'}]});
 expect(new Headers(init!.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
});

it('refuses invalid or duplicate local names and more than 64 tools',async()=>{
 control(Array.from({length:MAX_APPROVED_TOOLS+1},(_,i)=>definition(`tool_${i}`,D('b'))));
 render(<McpServers client={new ControlClient('token',vi.fn())}/>);await review();
 fireEvent.change(screen.getByLabelText('Local name for get_order'),{target:{value:'Get Order'}});
 await screen.findByText(/Local names must be unique and lowercase ToolGate names: Get Order/);
 fireEvent.change(screen.getByLabelText('Local name for get_order'),{target:{value:'get-order'}});
 for(let i=0;i<MAX_APPROVED_TOOLS;i++)fireEvent.click(screen.getByLabelText(`Approve tool_${i}`));
 await screen.findByText(/At most 64 tools per approval; 66 are selected/);
 expect((screen.getByRole('button',{name:'Approve 66 tools'}) as HTMLButtonElement).disabled).toBe(true);
});

it('reads but cannot change without mcp_server.register',async()=>{
 vi.mocked(ControlClient.prototype.can).mockImplementation(permission=>permission==='mcp_server.read');
 const fetcher=control();render(<McpServers client={new ControlClient('token',vi.fn())}/>);await review();
 expect((screen.getByRole('button',{name:'Register'}) as HTMLButtonElement).disabled).toBe(true);
 expect((screen.getByRole('button',{name:'Scan orders'}) as HTMLButtonElement).disabled).toBe(true);
 expect((screen.getByLabelText('Approve list_orders') as HTMLInputElement).disabled).toBe(true);
 expect(screen.queryByRole('button',{name:'Unregister orders'})).toBeNull();
 fireEvent.submit(screen.getByRole('button',{name:'Register'}).closest('form')!);
 expect(fetcher.mock.calls.some(([,init])=>init?.method==='POST')).toBe(false);
});

it('registers by URL with the pool binding and scans, and shows quarantine evidence',async()=>{
 const fetcher=control(undefined,[{...server,state:'QUARANTINED',quarantine:{reason:'DEFINITION_CHANGED',evidenceId:'scan-9',toolIds:['mcp.orders/get-order'],serverWide:false,atUnixMs:1,approvalSequence:1}}]);
 render(<McpServers client={new ControlClient('token',vi.fn())}/>);
 await screen.findByText('QUARANTINED (DEFINITION_CHANGED)');
 fireEvent.change(screen.getByLabelText(/^Name/),{target:{value:'billing'}});
 fireEvent.change(screen.getByLabelText('HTTPS URL'),{target:{value:'https://billing.example.com/mcp'}});
 fireEvent.change(screen.getByLabelText(/^Execution binding/),{target:{value:'mcp-binding'}});
 fireEvent.change(screen.getByLabelText(/^Vault secret/),{target:{value:'billing/token'}});
 fireEvent.change(screen.getByLabelText('Credential'),{target:{value:'HEADER'}});
 fireEvent.change(screen.getByLabelText('Header name'),{target:{value:'X-Api-Key'}});
 fireEvent.submit(screen.getByRole('button',{name:'Register'}).closest('form')!);
 await screen.findByText('Registered billing. Scan it to see its tools.');
 const [,init]=fetcher.mock.calls.find(([url,i])=>url.endsWith('/mcp-servers')&&i?.method==='POST')!;
 expect(JSON.parse(String(init!.body))).toEqual({slug:'billing',url:'https://billing.example.com/mcp',auth:{secretName:'billing/token',scheme:'HEADER',header:'X-Api-Key'},bindingId:'mcp-binding',strict:false});
 fireEvent.click(await screen.findByRole('button',{name:'Review orders'}));
 await screen.findByText(/Quarantined: DEFINITION_CHANGED/);
 fireEvent.click(screen.getByRole('button',{name:'Scan orders'}));
 await waitFor(()=>expect(fetcher.mock.calls.some(([url,i])=>url.endsWith('/mcp-servers/orders/scans')&&i?.method==='POST')).toBe(true));
});
