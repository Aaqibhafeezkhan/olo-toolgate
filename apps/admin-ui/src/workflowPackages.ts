// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {unzipSync} from 'fflate';
import type {DeploymentProfile,PackageManifest,PackageVersion,WorkflowBundleConnection,WorkflowBundlePackage} from '@olo-labs/toolgate-contracts/v2';
import type {ConfigurationFile} from './pendingContracts';

export const MAX_PACKAGE_BYTES=64*1024*1024,MAX_BUNDLE_BYTES=128*1024*1024;
export type EmbeddedPackage={metadata:WorkflowBundlePackage;bytes:Uint8Array;manifest:PackageManifest};
export async function sha256(bytes:Uint8Array){return 'sha256:'+Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new Uint8Array(bytes).buffer)),n=>n.toString(16).padStart(2,'0')).join('');}
const hex=(value:string)=>value.replace(/^sha256:/,'');

/** Resolve the exact archive containing each referenced descriptor; never substitute latest. */
export function requiredPackages(profile:DeploymentProfile,references:ConfigurationFile,connections:readonly WorkflowBundleConnection[],versions:readonly PackageVersion[]):PackageVersion[]{
  const selected=new Map<string,PackageVersion>();
  function one(candidates:readonly PackageVersion[],label:string){
    const unique=[...new Map(candidates.map(item=>[item.packageDigest,item])).values()];
    if(unique.length!==1||unique[0].state!=='VERIFIED')throw Error(`Required verified package is missing or ambiguous: ${label}`);
    selected.set(unique[0].packageDigest,unique[0]);
  }
  for(const pin of profile.packages)one(versions.filter(item=>item.packageId===pin.packageId&&item.version===pin.version&&(!pin.packageDigest||pin.packageDigest===item.packageDigest)),pin.packageId);
  for(const tool of references.tools)one(versions.filter(item=>item.manifest?.tools.some(entry=>entry.descriptor.toolId===tool.id&&(!tool.packageDigest||hex(entry.toolDescriptorDigest)===hex(tool.packageDigest)))),tool.id);
  const context=[...(profile.workflows?.workflows.flatMap(item=>item.contextSources??[])??[]),...(profile.workflows?.agentDefinitions.flatMap(item=>item.contextSources)??[])];
  for(const tool of context)if(!references.tools.some(item=>item.id===tool.toolId))one(versions.filter(item=>item.manifest?.tools.some(entry=>entry.descriptor.toolId===tool.toolId)),tool.toolId);
  for(const connection of connections)one(versions.filter(item=>item.version===connection.assignmentSpec.packageVersion&&item.manifest?.triggers?.some(entry=>entry.descriptor.triggerId===connection.assignmentSpec.trigger)),connection.assignmentSpec.trigger);
  const source=profile.workflows?.workflows[0].deployment?.source;
  if(source)one(versions.filter(item=>item.packageId===source.packageId&&item.version===source.version&&(!source.packageDigest||source.packageDigest===item.packageDigest)),source.packageId);
  if(selected.size>32)throw Error('Workflow requires more than 32 package archives.');
  return [...selected.values()].sort((a,b)=>a.packageDigest.localeCompare(b.packageDigest));
}

/** Integrity checks precede any upload. Control remains the authoritative package validator. */
export async function inspectPackage(bytes:Uint8Array):Promise<EmbeddedPackage>{
  if(!bytes.length||bytes.length>MAX_PACKAGE_BYTES)throw Error('Package archive exceeds the size limit.');
  let expanded=0;const names=new Set<string>();
  const files=unzipSync(bytes,{filter:entry=>{
    if(!/^[A-Za-z0-9_./-]+$/.test(entry.name)||entry.name.startsWith('/')||entry.name.split('/').some(part=>part==='.'||part==='..'||!part)||names.has(entry.name)||names.size>=1024||(expanded+=entry.originalSize)>128*1024*1024)throw Error('Invalid package archive inventory.');
    names.add(entry.name);return true;
  }});
  if(!files['toolgate-package.json']||!files['CHECKSUMS.sha256'])throw Error('Package inventory is missing.');
  const text=new TextDecoder('utf-8',{fatal:true});
  const checksums=text.decode(files['CHECKSUMS.sha256']),listed=new Set<string>();
  for(const line of checksums.trimEnd().split('\n')){
    const match=/^([a-f0-9]{64})  ([A-Za-z0-9_./-]+)$/.exec(line);
    if(!match||listed.has(match[2])||!files[match[2]]||await sha256(files[match[2]])!=='sha256:'+match[1])throw Error('Package content checksum mismatch.');
    listed.add(match[2]);
  }
  for(const name of names)if(name!=='CHECKSUMS.sha256'&&!name.startsWith('signatures/')&&!listed.has(name))throw Error('Unlisted package content.');
  const manifest=JSON.parse(text.decode(files['toolgate-package.json'])) as PackageManifest;
  if(manifest.schemaVersion!==2||typeof manifest.id!=='string'||typeof manifest.version!=='string')throw Error('Unsupported tool package.');
  const packageDigest=await sha256(files['CHECKSUMS.sha256']);
  return {metadata:{packageId:manifest.id,version:manifest.version,packageDigest,sha256:await sha256(bytes),bytes:bytes.length,path:'packages/'+hex(packageDigest)+'.olotool'},bytes,manifest};
}

export function verifyPackageCoverage(profile:DeploymentProfile,references:ConfigurationFile,connections:readonly WorkflowBundleConnection[],packages:readonly EmbeddedPackage[]){
  const catalog:PackageVersion[]=packages.map(item=>({packageId:item.metadata.packageId,version:item.metadata.version,packageDigest:item.metadata.packageDigest,descriptorSetDigest:item.manifest.descriptorSetDigest,state:'VERIFIED',revision:1,uploadedAtUnixMs:0,manifest:item.manifest}));
  const needed=requiredPackages(profile,references,connections,catalog);
  if(needed.length!==packages.length)throw Error('Workflow archive contains unrelated package artifacts.');
}
