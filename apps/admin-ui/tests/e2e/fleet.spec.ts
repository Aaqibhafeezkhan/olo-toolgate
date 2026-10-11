// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {readFileSync} from 'node:fs';

test.skip(!process.env.UI_TEST_FLEET, 'Requires the real direct-TLS fleet integration harness');
test.use({ignoreHTTPSErrors:true});
test('real fleet status, accessible assignment and revision-bound canary advancement',async({page})=>{
  const credentials=JSON.parse(readFileSync(process.env.UI_TEST_CREDENTIALS!,'utf8')) as {admin:string};
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.name));
  await page.goto('/console/');await page.getByLabel('Access token').fill(credentials.admin);
  await page.getByRole('button',{name:'Connect to workspace'}).click();
  await page.locator('nav summary').filter({hasText:'Devices'}).click();
  await page.getByRole('navigation').getByRole('link',{name:'Packages',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Package deployments'})).toBeVisible();
  await expect(page.getByRole('table')).toBeVisible();
  expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze()).violations).toEqual([]);
  await page.getByLabel('Approved release').selectOption({label:'fleet-echo@1.0.0'});
  await page.getByLabel('Enrolled device IDs, separated by commas').fill(process.env.UI_TEST_FLEET_DEVICE!);
  await page.getByLabel('Initial rollout percentage').fill('10');
  await page.getByRole('button',{name:'Create deployment',exact:true}).click();
  const advance=page.getByRole('button',{name:/Advance .* to 100%/});await expect(advance).toHaveCount(1);
  await advance.click();await expect(advance).toHaveCount(0);
  await page.getByLabel('Signed release JSON').fill('{}');await page.getByRole('button',{name:'Publish release',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('server rejected');
  expect(errors).toEqual([]);expect(await page.evaluate(()=>Object.keys(localStorage).filter(key=>!key.startsWith('toolgate.')).length)).toBe(0);
  expect(await page.locator('body').innerText()).not.toContain(credentials.admin);
});
