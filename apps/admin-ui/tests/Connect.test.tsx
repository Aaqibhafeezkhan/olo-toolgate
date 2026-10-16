// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {Connect,extensionStatus} from '../src/Connect';

afterEach(()=>{cleanup();vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals();});
const release={protocol:1,version:'0.10.0-dev',chromeVersion:'0.10.0.23',extensionId:'emmemldedebhbloibichmmdlbpjakfkf',storeUrl:'',filename:'olo-toolgate-chrome-0.10.0-dev-23.zip',sha256:'a'.repeat(64),bytes:123};
const setup={version:release.version,artifacts:[{platform:'WINDOWS',target:'x86_64-pc-windows-msvc',filename:'olo-toolgate-client-0.10.0-dev-x86_64-pc-windows-msvc.setup.exe',sha256:'b'.repeat(64),bytes:123}]};
function chrome(){vi.spyOn(navigator,'userAgent','get').mockReturnValue('Windows Chrome/140.0');vi.stubGlobal('fetch',vi.fn().mockImplementation((url:string)=>Promise.resolve(new Response(JSON.stringify(url.endsWith('/installers')?setup:release)))));}
function bridge(status:Record<string,unknown>){vi.spyOn(window,'postMessage').mockImplementation((message)=>{queueMicrotask(()=>window.dispatchEvent(new MessageEvent('message',{source:window,origin:location.origin,data:{channel:'toolgate-connect-response',id:message.id,...status}})));});}
it('rejects other browsers without starting a download',async()=>{
  vi.spyOn(navigator,'userAgent','get').mockReturnValue('Windows Edg/140 Chrome/140');const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher);
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText(/Currently supported/);expect(fetcher).not.toHaveBeenCalled();
});
it('guides an incompatible extension upgrade and cancels polling',async()=>{
  chrome();bridge({protocol:1,chromeVersion:'0.10.0.22',version:'0.10.0-dev'});
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText(/Upgrade the Chrome extension/);expect(screen.getByRole('link',{name:'Install Chrome extension and client'}).getAttribute('href')).toBe('/api/public/v1/clients/setup/x86_64-pc-windows-msvc');
  expect(screen.queryByRole('link',{name:'Download extension package for Chrome approval'})).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));await screen.findByText(/Connect cancelled/);
});
it('detects a client but requires separately verified enrollment',async()=>{
  chrome();bridge({protocol:1,chromeVersion:release.chromeVersion,version:release.version,phase:'approved',client:{health:{ready:true}},userCode:'ABCDEF0123456789'});
  const code=vi.fn();render(<Connect onCode={code}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText(/Client detected/);expect(code).not.toHaveBeenCalled();expect(screen.queryByText(/^Connected:/)).toBeNull();
});
it('does not claim an offline client has checked in or connected',async()=>{
  chrome();bridge({protocol:1,chromeVersion:release.chromeVersion,version:release.version,phase:'ready',serverUrl:'https://localhost:18450',client:{health:{ready:false,state:'OFFLINE'}}});
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText(/The client is offline/);
  expect(screen.queryByText(/confirms this device is connected|reports successful gateway check-ins/)).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
it('shows pending enrollment before confirming a connection',async()=>{
  chrome();bridge({protocol:1,chromeVersion:release.chromeVersion,version:release.version,phase:'ready',client:{health:{ready:false,state:'PENDING'}},userCode:'ABCDEF0123456789'});
  const code=vi.fn();render(<Connect onCode={code}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText(/Complete enrollment using the code/);expect(code).toHaveBeenCalledWith('ABCDEF0123456789');
  expect(screen.queryByText(/reports successful gateway check-ins/)).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
it('does not offer setup from a different client release',async()=>{
  chrome();bridge({protocol:1,chromeVersion:release.chromeVersion,version:release.version,phase:'approved'});
  vi.stubGlobal('fetch',vi.fn().mockImplementation((url:string)=>Promise.resolve(new Response(JSON.stringify(url.endsWith('/installers')?{...setup,version:'0.9.0'}:release)))));
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText(/Windows setup is not published/);
  expect(screen.queryByRole('link',{name:'Install Chrome extension and client'})).toBeNull();
  expect(screen.queryByRole('link',{name:'Download extension package for Chrome approval'})).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
it('offers the combined GNU debug installer and explicit Chrome enablement steps',async()=>{
  chrome();bridge({protocol:1,chromeVersion:release.chromeVersion,version:release.version,phase:'available'});
  const target='x86_64-pc-windows-gnu';
  const gnuSetup={...setup,artifacts:[{...setup.artifacts[0],target,filename:`olo-toolgate-client-${release.version}-${target}.setup.exe`}]};
  vi.stubGlobal('fetch',vi.fn().mockImplementation((url:string)=>Promise.resolve(new Response(JSON.stringify(url.endsWith('/installers')?gnuSetup:release)))));
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByRole('link',{name:'Install Chrome extension and client'});
  expect(screen.getByRole('link',{name:'Install Chrome extension and client'}).getAttribute('href')).toBe(`/api/public/v1/clients/setup/${target}`);
  expect(screen.getByRole('heading',{name:'Enable the Chrome extension for this local build'})).toBeTruthy();
  expect(screen.getByText('C:\\Program Files\\OLO\\ToolGateSetup\\chrome-extension')).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
it('rejects a malicious release URL',async()=>{
  chrome();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({...release,storeUrl:'https://evil.example'}))));
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));await screen.findByText(/Invalid extension release/);
  expect(screen.queryByRole('link')).toBeNull();
});
it('bridge ignores a different source, origin or correlation ID',async()=>{
  const sent=vi.spyOn(window,'postMessage').mockImplementation(()=>{});const controller=new AbortController();let done=false;
  const pending=extensionStatus(controller.signal).then(()=>{done=true;});
  const id=sent.mock.calls[0][0].id;
  for(const overrides of [{source:null},{origin:'https://evil.example'},{data:{channel:'toolgate-connect-response',id:'wrong'}}]){
    window.dispatchEvent(new MessageEvent('message',{source:window,origin:location.origin,data:{channel:'toolgate-connect-response',id},...overrides}));
  }
  await Promise.resolve();expect(done).toBe(false);
  window.dispatchEvent(new MessageEvent('message',{source:window,origin:location.origin,data:{channel:'toolgate-connect-response',id,protocol:1}}));
  await pending;await waitFor(()=>expect(done).toBe(true));
});
it('detects a newly installed extension and continues once without a page reload',async()=>{
  vi.useFakeTimers();chrome();let installed=false;
  const sent=vi.spyOn(window,'postMessage').mockImplementation(message=>{
    if(!installed)return;
    queueMicrotask(()=>window.dispatchEvent(new MessageEvent('message',{source:window,origin:location.origin,
      data:{channel:'toolgate-connect-response',id:message.id,protocol:1,version:release.version,chromeVersion:release.chromeVersion,phase:'available'}})));
  });
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await vi.advanceTimersByTimeAsync(2001);
  expect(screen.getByText(/This page will detect it automatically/)).toBeTruthy();
  installed=true;
  await vi.advanceTimersByTimeAsync(4000);
  expect(sent.mock.calls.filter(([message])=>message.operation==='connect')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
it('retries a client this gateway does not accept once from scratch',async()=>{
  vi.useFakeTimers();chrome();
  const sent=vi.spyOn(window,'postMessage').mockImplementation(message=>{
    queueMicrotask(()=>window.dispatchEvent(new MessageEvent('message',{source:window,origin:location.origin,
      data:{channel:'toolgate-connect-response',id:message.id,protocol:1,version:release.version,chromeVersion:release.chromeVersion,
        phase:'ready',serverUrl:'https://localhost:18450',client:{health:{ready:false,state:'OFFLINE'}}}})));
  });
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await vi.advanceTimersByTimeAsync(10000);
  expect(sent.mock.calls.filter(([message])=>message.operation==='connect')).toHaveLength(2);
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
it('reconnects a client that moved to another gateway',async()=>{
  vi.useFakeTimers();chrome();
  const sent=vi.spyOn(window,'postMessage').mockImplementation(message=>{
    queueMicrotask(()=>window.dispatchEvent(new MessageEvent('message',{source:window,origin:location.origin,
      data:{channel:'toolgate-connect-response',id:message.id,protocol:1,version:release.version,chromeVersion:release.chromeVersion,phase:'gateway-changed'}})));
  });
  render(<Connect onCode={vi.fn()}/>);fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await vi.advanceTimersByTimeAsync(6000);
  expect(screen.getByText(/Reconnecting it to this gateway/)).toBeTruthy();
  expect(sent.mock.calls.filter(([message])=>message.operation==='connect')).toHaveLength(2);
  fireEvent.click(screen.getByRole('button',{name:'Cancel Connect'}));
});
