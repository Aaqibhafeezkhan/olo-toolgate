// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {mkdirSync} from 'node:fs';

test('Super Admin authors nested workflow profiles in an accessible scrolling editor',async({page})=>{
  let staged:unknown;
  await page.route('**/api/control/v1/**',async route=>{
    const path=new URL(route.request().url()).pathname;
    let body:unknown={items:[]};
    if(path.endsWith('/admin-session'))body={role:'SUPER_ADMIN',permissions:['package.read']};
    if(path.endsWith('/workflow-profiles')){
      staged=route.request().postDataJSON();
      body={profileId:'example',profileDigest:'sha256:'+'a'.repeat(64),state:'DRAFT',generation:0,dependenciesReady:false};
    }
    await route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
  });
  await page.goto('/console/');await page.getByLabel('Access token').fill('fixture.test-only');
  await page.getByRole('button',{name:'Connect to workspace'}).click();await expect(page.getByRole('navigation')).toBeVisible();
  await page.evaluate(()=>{location.hash='#server-packages';});
  await page.getByRole('button',{name:'Manage workflow profile',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Manage workflow profile',exact:true});
  await dialog.getByLabel('profileId',{exact:true}).fill('example');
  await dialog.getByRole('button',{name:'Add workflows',exact:true}).click();
  for(const key of ['workflowGroups','modelDeployments','modelGroups','agentDefinitions','workflows','dispatchBindings'])await dialog.getByRole('button',{name:`Add ${key} item`,exact:true}).click();
  await expect(dialog.getByRole('group',{name:'limits',exact:true})).toBeVisible();
  await expect(dialog.getByLabel('systemPrompt',{exact:true})).toHaveCount(2);
  await dialog.getByRole('button',{name:'Remove workflows',exact:true}).click();
  await dialog.getByRole('button',{name:'Stage profile revision',exact:true}).click();
  await expect(dialog.getByText('example: DRAFT',{exact:true})).toBeVisible();
  expect(staged).toEqual({profileVersion:2,profileId:'example',packages:[]});
  await expect(dialog.getByRole('button',{name:'Publish reviewed revision'})).toBeDisabled();
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  await page.setViewportSize({width:390,height:844});
  expect(await dialog.locator('.editor-dialog-body').evaluate(el=>el.scrollWidth<=el.clientWidth)).toBe(true);
  mkdirSync('../../build/ui',{recursive:true});await page.screenshot({path:'../../build/ui/workflow-profile-mobile.png'});
  await dialog.getByRole('button',{name:'Close editor'}).click();
  await expect(page.getByRole('button',{name:'Manage workflow profile',exact:true})).toBeFocused();
});
