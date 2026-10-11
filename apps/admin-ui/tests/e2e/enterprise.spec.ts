// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {mkdirSync} from 'node:fs';

test.skip(!process.env.TOOLGATE_ENTERPRISE_ORIGIN,'Requires the isolated enterprise Quickstart gate');
test('real group administration, inherited provenance and review impact are accessible',async({page,browser})=>{
  test.setTimeout(360000);
  page.setDefaultTimeout(15000);
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.name));
  const origin=process.env.TOOLGATE_ENTERPRISE_ORIGIN!;
  await page.goto(origin+'/console/');
  await page.getByLabel('Password',{exact:true}).fill(process.env.TOOLGATE_ENTERPRISE_PASSWORD!);
  await page.getByRole('button',{name:'Connect to workspace',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();
  for(const [route,title] of [['users','Users'],['agents','Agents'],['agentGroups','Agent groups'],['toolGroups','Tool groups'],['deviceGroups','Device groups'],['grants','Access grants'],['bindings','Execution bindings'],['outcomes','Effect outcomes'],['simulation','Access simulation'],['config-reviews','Configuration reviews'],['config-server','Server'],['config-device','Device'],['config-backup','Backup/Restore']] as const){
    await page.goto(origin+'/console/#'+route);
    await expect(page.getByRole('heading',{name:title,exact:true})).toBeVisible();
    await expect(page.getByRole('status').filter({hasText:'Loading'})).toHaveCount(0);
    expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  }
  await page.goto(origin+'/console/#config-reviews');
  await expect(page.getByRole('heading',{name:'Configuration reviews',exact:true})).toBeVisible();
  await page.getByRole('table',{name:'Reviewed configuration changes',exact:true}).getByRole('button',{name:/^config-/}).first().click();
  await expect(page.getByRole('heading',{name:'Review configuration impact'})).toBeVisible();
  await expect(page.getByText('Exact change digest',{exact:true})).toBeVisible();
  await expect(page.getByRole('table',{name:'Exact record changes',exact:true})).toBeVisible();
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  mkdirSync('../../build/ui',{recursive:true});
  await page.screenshot({path:'../../build/ui/enterprise-configuration-desktop.png',fullPage:true});
  const contexts=await Promise.all([browser.newContext(),browser.newContext()]);
  try {
    const reviewers=await Promise.all(contexts.map(c=>c.newPage()));
    for(const [index,reviewer] of reviewers.entries()){
      reviewer.setDefaultTimeout(15000);
      await reviewer.goto(origin+'/console/');
      await reviewer.getByLabel('Local account').selectOption('reviewer-'+(index+1));
      await reviewer.getByLabel('Password',{exact:true}).fill(process.env['TOOLGATE_ENTERPRISE_REVIEWER_'+(index+1)]!);
      await reviewer.getByRole('button',{name:'Connect to workspace',exact:true}).click();
      await expect(reviewer.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();
    }
    async function applyDraft(){
      const link=page.getByRole('link',{name:'Review configuration draft'});
      const href=(await link.getAttribute('href'))!;
      await link.click();
      const confirmation='I reviewed the affected groups, inherited access, complete configuration and digests.';
      await page.getByLabel(confirmation).check();
      // Read the submission reply as it passes through: the page navigates right after it,
      // and Chrome then discards the body that waitForResponse would read.
      let requiredReviews:number|undefined;
      const submitted=(url:URL)=>url.pathname.includes('/configuration-changes/');
      await page.route(submitted,async route=>{
        if(route.request().method()!=='POST')return route.fallback();
        const response=await route.fetch();
        requiredReviews=(await response.json()).requiredReviews as number;
        await route.fulfill({response});
      });
      await page.getByRole('button',{name:'Submit draft for independent review'}).click();
      await expect(page.getByRole('button',{name:'Approve exact configuration'})).toBeVisible();
      await page.unroute(submitted);
      expect(requiredReviews).toBeGreaterThan(0);
      // The maker has management authority, but cannot approve their own change.
      await page.getByLabel(confirmation).check();
      await page.getByRole('button',{name:'Approve exact configuration'}).click();
      await expect(page.getByRole('alert')).toBeVisible();
      for(const reviewer of reviewers.slice(0,requiredReviews!)){
        await reviewer.goto(origin+'/console/'+href);
        await reviewer.getByLabel(confirmation).check();
        await reviewer.getByRole('button',{name:'Approve exact configuration'}).click();
        await expect(reviewer.getByLabel(confirmation)).not.toBeChecked();
      }
      await page.getByRole('button',{name:'Refresh changes'}).click();
      await expect(page.getByRole('button',{name:'Apply reviewed change'})).toBeVisible();
      await page.getByLabel(confirmation).check();
      await page.getByRole('button',{name:'Apply reviewed change'}).click();
      await expect(page.getByRole('button',{name:'Apply reviewed change'})).toHaveCount(0);
      await expect(page.getByRole('region',{name:'Configuration impact'}).getByText('APPLIED',{exact:true})).toBeVisible();
    }
    await page.goto(origin+'/console/#mapping');
    await page.getByRole('combobox',{name:'Agent Group',exact:true}).selectOption('default-agents');
    await page.getByRole('button',{name:'Add capability mapping'}).click();
    await page.getByLabel('Grant identifier').fill('browser-capability');
    await page.getByLabel('Mapping name').fill('Browser reviewed capability');
    await page.getByLabel('Enabled capability').check();
    for(const [label,id] of [['tool groups','default-tools'],['device groups','default-devices']]){
      await page.getByRole('button',{name:'Choose '+label,exact:true}).click();
      await page.getByRole('dialog').getByLabel(new RegExp(id)).check();
      await page.getByRole('dialog').getByRole('button',{name:'Apply selection'}).click();
    }
    await page.getByLabel('Actions, separated by commas').fill('evaluate');
    await page.getByRole('button',{name:'Add resource rule'}).click();
    await page.getByRole('combobox',{name:'Resource kind',exact:true}).selectOption('CUSTOM');
    await page.getByLabel('Resource locator').fill('builtin/calculator.evaluate');
    expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
    await page.getByRole('button',{name:'Save capability mapping'}).click();
    await applyDraft();
    await page.goto(origin+'/console/#mapping');
    await page.getByRole('combobox',{name:'Agent Group',exact:true}).selectOption('default-agents');
    await page.getByRole('button',{name:'Edit browser-capability',exact:true}).click();
    await expect(page.getByLabel('Resource locator')).toHaveValue('builtin/calculator.evaluate');
    console.info('PASS reviewed capability mapping persisted');
    await page.goto(origin+'/console/#users');
    await page.getByRole('button',{name:'Add user',exact:true}).click();
    await page.getByLabel('Identifier',{exact:true}).fill('browser-pending-user');
    await page.getByLabel('Display name').fill('Browser pending user');
    await expect(page.getByLabel('Enabled in directory')).not.toBeChecked();
    await page.getByRole('button',{name:'Save user',exact:true}).click();
    await applyDraft();
    await page.goto(origin+'/console/#users');
    await page.getByRole('button',{name:'Edit browser-pending-user',exact:true}).click();
    await expect(page.getByLabel('Enabled in directory')).not.toBeChecked();
    await expect(page.getByText('team-default',{exact:true}).first()).toBeVisible();
    console.info('PASS reviewed disabled user/default membership persisted');
    await page.goto(origin+'/console/#teams');
    await page.getByRole('button',{name:'Add team',exact:true}).click();
    await page.getByLabel('Identifier',{exact:true}).fill('browser-team');
    await page.getByLabel('Display name').fill('Browser team');
    await page.getByLabel('Enabled',{exact:true}).check();
    await page.getByRole('button',{name:'Save team',exact:true}).click();
    await applyDraft();
    console.info('PASS reviewed Team creation persisted');
    await page.goto(origin+'/console/#users');
    await page.getByRole('button',{name:'Edit browser-pending-user',exact:true}).click();
    const membership=page.getByRole('region',{name:'Group membership',exact:true});
    await membership.getByRole('button',{name:'Choose teams',exact:true}).click();
    await page.getByRole('dialog').getByLabel(/team-default/).uncheck();
    await page.getByRole('dialog').getByLabel(/browser-team/).check();
    await page.getByRole('dialog').getByRole('button',{name:'Apply selection'}).click();
    await membership.getByLabel('I reviewed the membership changes').check();
    await membership.getByRole('button',{name:'Save group membership'}).click();
    await applyDraft();
    await page.goto(origin+'/console/#users');
    await page.getByRole('button',{name:'Edit browser-pending-user',exact:true}).click();
    await expect(page.getByRole('region',{name:'Group membership',exact:true}).getByText('browser-team',{exact:true}).first()).toBeVisible();
    console.info('PASS reviewed membership transfer persisted');
    await page.goto(origin+'/console/#local');
    await expect(page.getByLabel('Tool',{exact:true}).locator('option')).toHaveCount(0);
  } finally { await Promise.all(contexts.map(c=>c.close())); }
  await page.goto(origin+'/console/#users');
  await page.getByRole('button',{name:'Edit admin',exact:true}).click();
  await expect(page.getByText(/Inherited access/).first()).toBeVisible();
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:'../../build/ui/enterprise-user-mobile.png',fullPage:true});
  expect(await page.evaluate(()=>({local:Object.keys(localStorage).filter(key=>!key.startsWith('toolgate.')).length,session:sessionStorage.length,cookies:document.cookie}))).toEqual({local:0,session:0,cookies:''});
  expect(errors).toEqual([]);
});
