// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
/** Explicit host build used by Gradle. Docker provides a verified prebuilt artifact. */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const root = fileURLToPath(new URL('../../',import.meta.url));
if (!process.argv.includes('--metadata-only')) {
  for (const args of [['ci','--ignore-scripts'],['run','ui:build']]) {
    // Windows batch launch receives only these two constant command strings.
    const result = process.platform === 'win32'
      ? spawnSync('cmd.exe',['/d','/s','/c',`npm ${args.join(' ')}`],{cwd:root,stdio:'inherit'})
      : spawnSync('npm',args,{cwd:root,stdio:'inherit'});
    if (result.status !== 0) process.exit(result.status ?? 1);
  }
}
const dist = join(root,'apps/admin-ui/dist');
writeFileSync(join(dist,'LICENSE.txt'),readFileSync(join(root,'LICENSE'),'utf8'));
writeFileSync(join(dist,'THIRD-PARTY-NOTICES.txt'),['react','react-dom','scheduler','fflate'].map(name => `${name}\n${readFileSync(join(root,'node_modules',name,'LICENSE'),'utf8')}`).join('\n\n'));
const assets = readdirSync(join(dist,'assets')).sort().map(file => ({file:`assets/${file}`,sha256:createHash('sha256').update(readFileSync(join(dist,'assets',file))).digest('hex')}));
writeFileSync(join(dist,'release.json'),JSON.stringify({version:readFileSync(join(root,'VERSION'),'utf8').trim(),contracts:readFileSync(join(root,'packages/contracts/VERSION'),'utf8').trim(),assets},null,2)+'\n');
