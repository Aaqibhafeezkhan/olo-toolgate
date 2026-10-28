// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import type { ClientDownloadManifest, ClientInstallerManifest } from '@olo-labs/toolgate-contracts';
test.skip(process.env.UI_TEST_DOWNLOADS_EXPECTED !== 'true', 'Requires a real three-platform release image');
// A fresh context per platform prevents Chromium's burst-download limiter from
// blocking later files on fast Linux runners. Each test still downloads both CPUs.
for (const platform of ['WINDOWS','MACOS','LINUX'] as const) {
test(`anonymous ${platform} downloads have matching checksums and accessible states`, async ({ page, request }) => {
  await page.goto('/');
  await expect(page.getByRole('heading',{name:'Install ToolGate on your computer'})).toBeVisible();
  await expect(page.getByLabel('Access token')).toBeVisible();
  const response = await request.get('/api/public/v1/clients'); expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toBe('no-store');
  const manifest = await response.json() as ClientDownloadManifest;
  const labels = {WINDOWS:'Windows',MACOS:'macOS',LINUX:'Linux'};
  const native=manifest.artifacts.filter(artifact=>artifact.platform===platform);
  expect(native.length).toBeGreaterThan(0);
  if (platform === 'WINDOWS') await expect(page.locator('a[href$=".zip"]')).toHaveCount(0);
  for (const artifact of native.filter(a=>a.platform !== 'WINDOWS')) {
    const architecture=artifact.target.startsWith('aarch64')?'ARM64':'x64';
    const pending = page.waitForEvent('download'); await page.getByRole('link',{name:`Download ${labels[artifact.platform]} ${architecture}`,exact:true}).click();
    const download = await pending; expect(download.suggestedFilename()).toBe(artifact.filename);
    const path = await download.path(); const data = await readFile(path!);
    expect(createHash('sha256').update(data).digest('hex')).toBe(artifact.sha256);
    expect(data.length).toBe(artifact.bytes);
  }
  const installerResponse = await request.get('/api/public/v1/installers'); expect(installerResponse.status()).toBe(200);
  expect(installerResponse.headers()['cache-control']).toBe('no-store');
  const installers = await installerResponse.json() as ClientInstallerManifest;
  expect(installers.version).toBe(manifest.version);
  const setups=installers.artifacts.filter(installer=>installer.platform===platform);
  expect(setups.length).toBe(native.length);
  for (const installer of setups) {
    const suffix={WINDOWS:'setup.exe',MACOS:'dmg',LINUX:'run'}[installer.platform];
    const stable=`olo-toolgate-client-${installer.target}.${suffix}`;
    const setupPath=platform==='WINDOWS'?`/api/public/v1/clients/setup/${installer.target}`:`/api/public/v1/clients/${stable}`;
    const architecture=installer.target.startsWith('aarch64')?'ARM64':'x64';
    const link=page.getByRole('link',{name:`Install ${labels[installer.platform]} ${architecture}`,exact:true});
    await expect(link).toHaveAttribute('href',setupPath);
    const stableResponse=await request.get(`/api/public/v1/clients/${stable}`);
    expect(stableResponse.status()).toBe(200);
    expect(stableResponse.headers()['cache-control']).toBe('no-store');
    expect(stableResponse.headers()['content-disposition']).toBe(`attachment; filename="${stable}"`);
    expect(createHash('sha256').update(await stableResponse.body()).digest('hex')).toBe(installer.sha256);
    const pending = page.waitForEvent('download'); await link.click();
    const download = await pending;
    if(platform==='WINDOWS')expect(download.suggestedFilename()).toMatch(new RegExp(`^olo-toolgate-client-${installer.target}--[a-f0-9]+\\.setup\\.exe$`));
    else expect(download.suggestedFilename()).toBe(stable);
    const data = await readFile((await download.path())!);
    expect(createHash('sha256').update(data).digest('hex')).toBe(installer.sha256);
    expect(data.length).toBe(installer.bytes);
    const versioned=await request.get(`/api/public/v1/clients/${installer.filename}`);
    expect(versioned.status()).toBe(200);
    if (manifest.version.includes('-')) expect(versioned.headers()['cache-control']).toBe('no-store');
    else expect(versioned.headers()['cache-control']).toBe('public, max-age=31536000, immutable');
    expect((await versioned.body()).equals(data)).toBe(true);
  }
  expect((await request.get('/api/public/v1/clients/olo-toolgate-client-unknown.setup.exe')).status()).toBe(404);
  await expect(page.getByText(/These development installers are unsigned/)).toBeVisible();
  expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  await page.setViewportSize({width:390,height:844});
  await expect(page.getByRole('link',{name:'Download Linux x64'})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
}
