// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect,type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {readFileSync} from 'node:fs';
import type {WorkflowConfiguration} from '@olo-labs/toolgate-contracts/v2';

const digest='sha256:'+'a'.repeat(64);
const configuration=JSON.parse(readFileSync(new URL('../../../../tests/fixtures/contracts/v2/valid.json',import.meta.url),'utf8')).WorkflowConfiguration as WorkflowConfiguration;
const profile={profileVersion:2,profileId:'support',packages:[],workflows:configuration};
const status={profileId:'support',profileDigest:digest,state:'REVIEWED',generation:1,dependenciesReady:true};
async function connect(page:Page,role:string){
  let catalogRequests=0;
  await page.route('**/api/control/v1/**',async route=>{
    const path=new URL(route.request().url()).pathname;
    let body:unknown={items:[]};
    if(path.endsWith('/admin-session'))body={role,permissions:['agent.read','package.read']};
    if(path.endsWith('/workflow-profiles')){catalogRequests++;body=[status];}
    if(path.includes('/revisions/'))body=path.endsWith('/document')?profile:status;
    await route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
  });
  await page.goto('/console/');await page.getByLabel('Access token').fill('fixture.test-only');
  await page.getByRole('button',{name:'Connect to workspace'}).click();await expect(page.getByRole('navigation')).toBeVisible();
  return ()=>catalogRequests;
}
test('dedicated workflow, group and agent pages share the selected revision and draft edits',async({page})=>{
  await connect(page,'SUPER_ADMIN');
  await page.locator('nav summary').filter({hasText:/^Workflows$/}).click();
  await page.getByRole('link',{name:'Workflow groups',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Workflow groups',level:1})).toBeVisible();
  await page.getByRole('button',{name:'Open support aaaaaaaaaaaa'}).click();
  await page.getByRole('button',{name:`Edit ${configuration.workflowGroups[0].id}`,exact:true}).click();
  const editor=page.getByRole('dialog',{name:'Configure workflow groups'});
  await editor.getByLabel('displayName').fill('Support operations');
  await editor.getByRole('button',{name:'Apply to profile draft'}).click();
  await expect(page.getByText('Support operations',{exact:true})).toBeVisible();
  await page.getByRole('link',{name:'Workflows',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Workflows',level:1})).toBeVisible();
  await expect(page.getByText('Unsaved profile draft',{exact:false})).toBeVisible();
  await page.locator('nav summary').filter({hasText:/^Agents$/}).click();
  await page.getByRole('link',{name:'Prompts & context',exact:true}).click();
  await page.getByRole('button',{name:`Edit ${configuration.agentDefinitions[0].agentId}`,exact:true}).click();
  const agent=page.getByRole('dialog',{name:'Configure agent prompts and context'});
  await expect(agent.getByLabel('systemPrompt')).toBeVisible();await expect(agent.getByLabel('modelGroupId')).toBeVisible();
  await expect(agent.getByLabel('outputSchema')).toBeVisible();
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  await agent.getByRole('button',{name:'Close editor'}).click();
  await page.getByRole('link',{name:'Workflow mappings',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Agent membership and delegation in workflows'})).toBeVisible();
  await page.getByRole('link',{name:'Workflow groups',exact:true}).click();
  await expect(page.getByText('Support operations',{exact:true})).toBeVisible();
});
test('scoped administrators cannot open the workflow catalog via a deep link',async({page})=>{
  const requests=await connect(page,'ADMINISTRATOR');
  await page.evaluate(()=>{location.hash='#workflows';});
  await expect(page.getByRole('heading',{name:'Access unavailable'})).toBeVisible();
  await expect(page.getByRole('link',{name:'Workflow groups'})).toHaveCount(0);
  expect(requests()).toBe(0);
});
