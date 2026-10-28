// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// Production UI + real Chromium downloads against explicit inert HTTP fixtures.
// This complements, rather than replaces, CI's real Control container proof.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';

const npm=process.platform==='win32'?'npm.cmd':'npm';
async function run(args,env=process.env) {
  await new Promise((done,reject)=>{
    const child=spawn(npm,args,{stdio:'inherit',shell:process.platform==='win32',env});
    child.on('error',reject);child.on('exit',code=>code===0?done():reject(Error(`Browser gate failed: ${code}`)));
  });
}
if (!process.argv.includes('--skip-build')) await run(['run','ui:build']);
const version=JSON.parse(await readFile('package.json','utf8')).version;
async function verifyDownloads(version,experience=false) {
  const archives=[],installers=[],assets=new Map();
  for(const [platform,os,suffix] of [['WINDOWS','pc-windows-msvc','setup.exe'],['MACOS','apple-darwin','dmg'],['LINUX','unknown-linux-gnu','run']]) {
    for(const architecture of ['x86_64','aarch64']) {
      const target=`${architecture}-${os}`;
      for(const setup of [false,true]) {
        // Clearly inert bytes: no fabricated executable is installed or released.
        const bytes=Buffer.from(`ToolGate browser test fixture only: ${target} ${setup}\n`);
        const filename=`olo-toolgate-client-${version}-${target}.${setup?suffix:platform==='WINDOWS'?'zip':'tar.gz'}`;
        const artifact={platform,target,filename,sha256:createHash('sha256').update(bytes).digest('hex'),bytes:bytes.length};
        (setup?installers:archives).push(artifact);
        assets.set(filename,{bytes,stable:false});
        if(setup)assets.set(`olo-toolgate-client-${target}.${suffix}`,{bytes,stable:true});
      }
    }
  }
  const dist=resolve('apps/admin-ui/dist');
  const server=createServer(async(req,res)=>{
    const path=new URL(req.url,'http://127.0.0.1').pathname;
    res.setHeader('X-Content-Type-Options','nosniff');
    const manifest=path==='/api/public/v1/clients'?archives:path==='/api/public/v1/installers'?installers:undefined;
    if(manifest){res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');res.end(JSON.stringify({version,artifacts:manifest}));return;}
    if(path.startsWith('/api/public/v1/clients/setup/')) {
      const target=path.substring('/api/public/v1/clients/setup/'.length);
      const asset=assets.get(`olo-toolgate-client-${target}.setup.exe`);
      if(!asset){res.writeHead(404);res.end();return;}
      const hint=Buffer.from('https://control.example.test').toString('hex');
      res.setHeader('Content-Type','application/octet-stream');
      res.setHeader('Content-Disposition',`attachment; filename="olo-toolgate-client-${target}--${hint}.setup.exe"`);
      res.setHeader('Cache-Control','no-store');res.end(asset.bytes);return;
    }
    if(path.startsWith('/api/public/v1/clients/')) {
      const name=decodeURIComponent(path.substring('/api/public/v1/clients/'.length));const asset=assets.get(name);
      if(!asset){res.writeHead(404);res.end();return;}
      res.setHeader('Content-Type','application/octet-stream');res.setHeader('Content-Disposition',`attachment; filename="${name}"`);
      res.setHeader('Cache-Control',asset.stable||version.includes('-')?'no-store':'public, max-age=31536000, immutable');
      res.setHeader('Content-Length',asset.bytes.length);res.end(asset.bytes);return;
    }
    if(path.startsWith('/api/')){res.writeHead(404);res.end();return;}
    const file=resolve(dist,path==='/'||path==='/console/'?'index.html':'.'+path.replace(/^\/console/,'').replace(/^\//,sep));
    if(!file.startsWith(dist+sep)){res.writeHead(404);res.end();return;}
    try {const data=await readFile(file);res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'})[extname(file)]??'application/octet-stream');res.end(data);}
    catch{res.writeHead(404);res.end();}
  });
  await new Promise(done=>server.listen(0,'127.0.0.1',done));
  try {
    console.log(`Browser download cache policy: ${version}`);
    await run(['--workspace','@olo-labs/toolgate-admin-ui','run','e2e','--','downloads.spec.ts',...(experience?['experience.spec.ts']:[])],
      {...process.env,UI_TEST_ORIGIN:`http://127.0.0.1:${server.address().port}`,UI_TEST_DOWNLOADS_EXPECTED:'true',UI_TEST_EXPERIENCE:String(experience)});
  } finally {await new Promise(done=>server.close(done));}
}
// Exercise both policies regardless of whether the checkout is a prerelease or final release.
await verifyDownloads(version,true);
await verifyDownloads(version.includes('-')?version.split('-')[0]:`${version}-dev`);
