// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {expect,it,vi} from 'vitest';
import {ApiError,ControlClient} from '../src/api';
const digest='sha256:'+'a'.repeat(64),media='application/vnd.ololabs.toolgate.package+zip';

it('exports exact package bytes with tenant bearer auth, encoded digest and no browser credentials',async()=>{
  const transport=vi.fn<typeof fetch>().mockResolvedValue(new Response(new Uint8Array([1,2,3]),{headers:{'Content-Type':media}}));
  const client=new ControlClient('private-token',vi.fn(),transport);
  expect(await client.exportServerPackage(digest)).toEqual(new Uint8Array([1,2,3]));
  const [path,options]=transport.mock.calls[0];
  expect(path).toBe('/api/control/v1/packages/artifacts/sha256%3A'+'a'.repeat(64));
  expect(options).toMatchObject({credentials:'omit',cache:'no-store',redirect:'error'});
  expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer private-token');
  await expect(client.exportServerPackage('../escape')).rejects.toBeInstanceOf(ApiError);
  expect(transport).toHaveBeenCalledTimes(1);
});
it('ends an unauthorized package session and refuses failed or non-package responses',async()=>{
  const expired=vi.fn();const transport=vi.fn<typeof fetch>().mockResolvedValue(new Response('{}',{status:401}));
  await expect(new ControlClient('token',expired,transport).exportServerPackage(digest)).rejects.toMatchObject({status:401});
  expect(expired).toHaveBeenCalledOnce();
  for(const response of [new Response('{}',{status:403}),new Response('{}',{headers:{'Content-Type':'application/json'}}),new Response(null,{headers:{'Content-Type':media}})]){
    const send=vi.fn<typeof fetch>().mockResolvedValue(response);
    await expect(new ControlClient('token',vi.fn(),send).exportServerPackage(digest)).rejects.toBeInstanceOf(ApiError);
    expect(send).toHaveBeenCalledOnce();
  }
});
it('cancels an oversized streamed archive before assembling a download',async()=>{
  const cancel=vi.fn();const body=new ReadableStream({start(controller){controller.enqueue(new Uint8Array(64*1024*1024+1));},cancel});
  const send=vi.fn<typeof fetch>().mockResolvedValue(new Response(body,{headers:{'Content-Type':media}}));
  await expect(new ControlClient('token',vi.fn(),send).exportServerPackage(digest)).rejects.toMatchObject({status:413});
  expect(cancel).toHaveBeenCalledOnce();
});
it('accepts .olotool package uploads and rejects obsolete named files before sending bytes',async()=>{
  const transport=vi.fn<typeof fetch>().mockResolvedValue(new Response('{}',{headers:{'Content-Type':'application/json'}}));
  const client=new ControlClient('token',vi.fn(),transport);
  expect(()=>client.uploadServerPackage(new File(['bytes'],'old.tgpkg'))).toThrow(ApiError);
  expect(()=>client.uploadServerPackage(new File(['bytes'],'workflow.olo'))).toThrow(ApiError);
  expect(transport).not.toHaveBeenCalled();
  await client.uploadServerPackage(new File(['bytes'],'tool.olotool'));
  expect(transport).toHaveBeenCalledOnce();
});
