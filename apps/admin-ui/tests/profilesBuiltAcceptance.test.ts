// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {readFileSync,readdirSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {expect,it} from 'vitest';
import {importWorkflow} from '../src/workflowBundle';

const directory=process.env.PROFILE_BUNDLE_DIRECTORY;
it.runIf(Boolean(directory))('imports every retained repository profile and exact embedded tool bytes',async()=>{
  const folder=resolve(process.cwd(),'../..',directory!);
  const index=readdirSync(folder).find(name=>name.startsWith('olo-profiles-')&&name.endsWith('.json'))!;
  const inventory=JSON.parse(readFileSync(join(folder,index),'utf8')) as {version:string;profiles:{filename:string;workflowId:string;packages:unknown[]}[]};
  expect(inventory.version).toBe(process.env.PROFILE_BUNDLE_VERSION);
  const sources=resolve(process.cwd(),'../../bot/configurations');
  const expected=readdirSync(sources).reduce((count,name)=>count+(JSON.parse(readFileSync(join(sources,name,'bot.json'),'utf8')) as {workflows:{workflows:unknown[]}}).workflows.workflows.length,0);
  expect(inventory.profiles).toHaveLength(expected);
  for(const entry of inventory.profiles){
    const bundle=await importWorkflow(entry.filename,new Uint8Array(readFileSync(join(folder,entry.filename))));
    expect(bundle.profile.workflows!.workflows[0].id).toBe(entry.workflowId);
    expect(bundle.packages.map(item=>item.metadata)).toEqual(entry.packages);
    expect(bundle.packages.length).toBeGreaterThan(0);
  }
},120000);
