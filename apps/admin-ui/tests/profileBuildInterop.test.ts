// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,rmSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve,sep} from 'node:path';
import {expect,it} from 'vitest';
import type {DeploymentProfile} from '@olo-labs/toolgate-contracts/v2';
import {exportWorkflow,importWorkflow} from '../src/workflowBundle';
import {inspectPackage} from '../src/workflowPackages';
import type {ConfigurationFile} from '../src/pendingContracts';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';

it('imports Python-built profiles and verifies console exports with the Python release validator',async()=>{
  const root=resolve(process.cwd(),'../..');
  const work=mkdtempSync(join(tmpdir(),'olo-profile-interop-'));
  try{
    const bytes=new Uint8Array(readFileSync(join(root,'tests/fixtures/packages/cases/valid-deflated.olo')));
    const embedded=await inspectPackage(bytes);
    const profile=structuredClone({profileVersion:2,profileId:'interop',packages:[],workflows:fixture.WorkflowConfiguration}) as unknown as DeploymentProfile;
    const config=JSON.parse(JSON.stringify(profile.workflows)) as NonNullable<DeploymentProfile['workflows']>;
    const selected={...profile,packages:[{packageId:embedded.metadata.packageId,version:embedded.metadata.version,source:'MARKETPLACE' as const,required:true,packageDigest:embedded.metadata.packageDigest}],workflows:{...config,dispatchBindings:[],agentDefinitions:config.agentDefinitions.map((item,index)=>({...item,contextSources:index===0?[{toolId:embedded.manifest.tools[0].descriptor.toolId,arguments:{}}]:[]}))}};
    const references={formatVersion:2,tenantId:'destination',revision:0,agents:[],agentGroups:[],roles:[],grants:[],tools:[],toolGroups:[],deviceGroups:[],bindings:[],policies:[],extractors:[],teams:[],devices:[],delegations:[],agentDelegations:[],workloadBindings:[]} as ConfigurationFile;
    writeFileSync(join(work,'profile.json'),JSON.stringify(selected));writeFileSync(join(work,'references.json'),JSON.stringify(references));
    const python=process.platform==='win32'?'python':'python3';
    execFileSync(python,['-c',"import json,sys;from pathlib import Path;from profiles.bundle import pack;p=Path(sys.argv[1]);data,_=pack(json.loads((p/'profile.json').read_text()),json.loads((p/'references.json').read_text()),[],[Path('tests/fixtures/packages/cases/valid-deflated.olo').read_bytes()],dict(version='1.0.0',sourceCommit='a'*40,build='test'));(p/'python.olo').write_bytes(data)",work],{cwd:root});
    const loaded=await importWorkflow('python.olo',new Uint8Array(readFileSync(join(work,'python.olo'))));
    expect(loaded.packages[0].bytes).toEqual(bytes);
    expect(loaded.profile.workflows!.workflows).toHaveLength(1);
    const output=await exportWorkflow(selected,selected.workflows.workflows[0].id,[],[],references,[bytes]);
    writeFileSync(join(work,'console.olo'),output);
    const verified=execFileSync(python,['-c',"import sys;from pathlib import Path;from profiles.bundle import verify;m=verify(Path(sys.argv[1]).read_bytes());print(m['version'],len(m['packages']))",join(work,'console.olo')],{cwd:root,encoding:'utf8'});
    expect(verified.trim()).toBe('2 1');
  }finally{
    if(!resolve(work).startsWith(resolve(tmpdir())+sep))throw Error('Unexpected test output directory');
    rmSync(work,{recursive:true,force:true});
  }
});
