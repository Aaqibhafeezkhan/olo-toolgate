// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {readFileSync} from 'node:fs';

test.skip(!process.env.UI_TEST_BUILDER,'Requires real TLS login and a configured JSON SDK build worker');
test.use({ignoreHTTPSErrors:true});
test('author a canonical SDK project and download its package and source',async({page})=>{
 test.setTimeout(180000);
 const credentials=JSON.parse(readFileSync(process.env.UI_TEST_CREDENTIALS!,'utf8')) as {admin:string};
 await page.goto('/console/');await page.getByLabel('Access token').fill(credentials.admin);await page.getByRole('button',{name:'Connect to workspace'}).click();
 await page.locator('nav summary').filter({hasText:'Tools'}).click();
 await page.getByRole('navigation').getByRole('link',{name:'Tool builder',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Tool Builder'})).toBeVisible();
 expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze()).violations).toEqual([]);
 await page.getByRole('button',{name:'Add project'}).click();
 const project=JSON.parse(await page.getByLabel('JSON SDK project').inputValue());project.id=`browser.hello-${Date.now()}`;project.name='Browser Hello World';
 await page.getByLabel('JSON SDK project').fill(JSON.stringify(project,null,2));
 await page.getByRole('button',{name:'Save project'}).click();await expect(page.getByRole('status').filter({hasText:'Project saved at revision 1.'})).toBeVisible();
 await page.getByRole('button',{name:'Build package'}).click();await expect(page.getByRole('status').filter({hasText:'Build queued.'})).toBeVisible();
 await page.getByRole('button',{name:'Close editor'}).click();
 const packageButton=page.getByRole('button',{name:/Download package /}).first();await expect(packageButton).toBeVisible({timeout:150000});
 const packageDownload=page.waitForEvent('download');await packageButton.click();expect((await packageDownload).suggestedFilename()).toBe('tool-package.tgpkg');
 const sourceDownload=page.waitForEvent('download');await page.getByRole('button',{name:/Download source /}).first().click();expect((await sourceDownload).suggestedFilename()).toBe('tool-source.zip');
 expect(await page.locator('body').innerText()).not.toContain(credentials.admin);
});
