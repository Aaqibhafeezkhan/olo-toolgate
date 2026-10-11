// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mkdirSync, readFileSync } from 'node:fs';
const credentials = JSON.parse(readFileSync(process.env.UI_TEST_CREDENTIALS!, 'utf8')) as Record<string,string>;
const ids = JSON.parse(process.env.UI_TEST_APPROVAL_IDS!) as Record<'once'|'temporary'|'deny'|'conflict',string>;

async function accessible(page:Page) { expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze()).violations).toEqual([]); }
async function review(page:Page, id:string) {
  await page.getByRole('button',{name:`Review approval ${id}`,exact:true}).click();
  await expect(page.getByRole('heading',{name:'Choose a decision'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Review exact operation'})).toBeFocused();
}
async function confirm(page:Page, choice:string) {
  const button = page.getByRole('button',{name:`Confirm ${choice}`,exact:true}); await expect(button).toBeDisabled();
  await page.getByLabel('I reviewed the identities, resource, arguments digest and policy version.').check();
  await button.click();
}

test('real ASK queue, once/temporary/deny decisions, stale revision and accessible exact scope', async ({page,request}) => {
  const errors:string[]=[]; page.on('pageerror',error => errors.push(error.name));
  await page.goto('/console/'); await page.getByLabel('Access token').fill(credentials.approver);
  await page.getByRole('button',{name:'Connect to workspace'}).click();
  // This dedicated approver has no directory reader/admin role. Control verifies
  // the session through the approval API rather than client-side JWT inspection.
  await expect(page.getByRole('heading',{name:'Approvals',exact:true})).toBeVisible();
  await expect(page.getByRole('navigation').getByRole('link',{name:'Approvals',exact:true})).toHaveAttribute('aria-current','page');
  await expect(page.getByRole('table')).toBeVisible(); await accessible(page);
  await review(page,ids.once);
  const details=page.getByRole('region',{name:'Approval details'});
  for(const label of ['Tenant','Requester','Agent','Device','Tool','Action','Exact resource','Arguments digest','Policy version','Expires']) await expect(details.getByText(label,{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Approve once',exact:true}).click(); await accessible(page);
  mkdirSync('../../build/ui',{recursive:true}); await page.screenshot({path:'../../build/ui/approvals-desktop.png',fullPage:true});
  await confirm(page,'approve once'); await expect(details.getByText('Approved once',{exact:true})).toBeVisible();
  await expect(details.getByRole('button',{name:'Approve once',exact:true})).toHaveCount(0);
  await expect(details.getByRole('status')).toContainText('requester retries through Gateway');
  await page.getByRole('button',{name:'Close approval'}).click();

  await review(page,ids.temporary); await page.getByRole('button',{name:'Approve temporarily',exact:true}).click();
  await page.getByLabel('Maximum approval duration').selectOption('600000'); await accessible(page);
  await confirm(page,'approve temporarily'); await expect(details.getByText('Approved temporarily',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Close approval'}).click();
  await review(page,ids.deny); await page.getByRole('button',{name:'Deny request',exact:true}).click(); await confirm(page,'deny');
  await expect(details.getByRole('status')).toContainText('No execution permission was granted');
  await page.getByRole('button',{name:'Close approval'}).click();

  await review(page,ids.conflict); await page.getByRole('button',{name:'Approve once',exact:true}).click();
  const current=await request.get(`/api/control/v1/approvals/${encodeURIComponent(ids.conflict)}`,{headers:{Authorization:`Bearer ${credentials.approver}`}});
  expect(current.status()).toBe(200); const stored=await current.json() as {revision:number};
  const concurrent=await request.post(`/api/control/v1/approvals/${encodeURIComponent(ids.conflict)}/decision`,{headers:{Authorization:`Bearer ${credentials.approver}`,'Idempotency-Key':'browser-approval-conflict'},data:{decision:'DENY',expectedRevision:stored.revision}});
  expect(concurrent.status()).toBe(200);
  await confirm(page,'approve once'); await expect(details.getByRole('alert')).toContainText('Refresh before trying again');
  await expect(details.getByText('Denied',{exact:true})).toBeVisible(); await expect(details.getByRole('button',{name:'Approve once',exact:true})).toHaveCount(0); await accessible(page);
  await page.setViewportSize({width:390,height:844}); await accessible(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({path:'../../build/ui/approvals-mobile.png',fullPage:true});
  expect(await page.evaluate(() => ({local:Object.keys(localStorage).filter(key=>!key.startsWith('toolgate.')).length,session:sessionStorage.length,cookies:document.cookie}))).toEqual({local:0,session:0,cookies:''});
  await page.reload(); await expect(page.getByLabel('Access token')).toBeVisible(); expect(errors).toEqual([]);
});

test('directory reader does not gain approver permission from the console', async ({page}) => {
  await page.goto('/console/'); await page.getByLabel('Access token').fill(credentials.reader);
  await page.getByRole('button',{name:'Connect to workspace'}).click();
  await expect(page.getByRole('alert')).toContainText('permission'); await expect(page.getByRole('navigation')).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Approve once',exact:true})).toHaveCount(0); await accessible(page);
});
