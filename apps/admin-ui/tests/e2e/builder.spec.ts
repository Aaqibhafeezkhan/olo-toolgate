// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {readFileSync} from 'node:fs';
test.skip(!process.env.UI_TEST_BUILDER,'Requires the real TLS and designated-client harness');
test.use({ignoreHTTPSErrors:true});
test('author, scan, test on client, seal and prepare publication',async({page})=>{
 test.setTimeout(120000);
 const credentials=JSON.parse(readFileSync(process.env.UI_TEST_CREDENTIALS!,'utf8')) as {admin:string};
 await page.goto('/console/');await page.getByLabel('Access token').fill(credentials.admin);await page.getByRole('button',{name:'Connect to workspace'}).click();
 await page.locator('nav summary').filter({hasText:'Tools'}).click();
  await page.getByRole('navigation').getByRole('link',{name:'Tool builder',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Custom tool builder'})).toBeVisible();
 expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze()).violations).toEqual([]);
 await page.getByLabel('Package ID',{exact:true}).fill('browser-tool');await page.getByLabel('Tool ID',{exact:true}).fill('custom.ui');
 await page.getByLabel('Name',{exact:true}).fill('Browser authored echo');
 await page.getByLabel('Approved digest-pinned runtime image').fill(process.env.UI_TEST_BUILDER_IMAGE!);await page.getByLabel('Exact runtime version').fill(process.env.UI_TEST_BUILDER_VERSION!);
 await page.getByRole('button',{name:'Save and scan draft'}).click();await expect(page.getByRole('status').filter({hasText:'Draft saved and scanned.'})).toBeVisible();
 await page.getByLabel('Designated enrolled client IDs, comma separated').fill(process.env.UI_TEST_FLEET_DEVICE!);await page.getByRole('button',{name:'Test on designated client'}).click();
 await expect.poll(async()=>{await page.getByRole('button',{name:'Refresh tests'}).click();return await page.getByRole('list',{name:'Test results'}).innerText();},{timeout:90000,intervals:[2000]}).toContain('PASSED');
 const descriptor=page.waitForEvent('download');await page.getByRole('button',{name:'Create organization package'}).click();expect((await descriptor).suggestedFilename()).toBe('browser-tool-1.0.0.json');
 await expect(page.getByLabel('Code editor')).toBeDisabled();
 const publication=page.waitForEvent('download');await page.getByRole('button',{name:'Prepare publication'}).click();expect((await publication).suggestedFilename()).toBe('browser-tool-publication.json');
 await page.getByRole('button',{name:'Clone to new version'}).click();await expect(page.getByLabel('Version',{exact:true})).toHaveValue('');await expect(page.getByLabel('Code editor')).toBeEnabled();
 expect(await page.evaluate(()=>Object.keys(localStorage).filter(key=>!key.startsWith('toolgate.')).length)).toBe(0);expect(await page.locator('body').innerText()).not.toContain(credentials.admin);
});
