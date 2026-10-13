// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('request page fills its parent canvas and shows a completed response safely',async({page})=>{
  test.skip(!process.env.QUICKSTART_PASSWORD,'Requires actual composed Quickstart fixture');
  const record={requestId:'canvas-request',toolId:'client.read_log_entry',agentId:'test-agent',deviceId:'test-client',state:'DONE',receivedAtUnixMs:1,expiresAtUnixMs:30001,completedAtUnixMs:4};
  await page.route('**/api/control/v1/mcp/requests',route=>route.fulfill({json:{items:[record]}}));
  await page.route('**/api/control/v1/mcp/requests/canvas-request',route=>route.fulfill({json:{record,output:{text:'<script>display this as text</script>'}}}));
  await page.goto('/console/');await page.getByLabel('Password',{exact:true}).fill(process.env.QUICKSTART_PASSWORD!);
  await page.getByRole('button',{name:'Connect to workspace'}).click();await expect(page.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();
  await page.goto('/console/#requests');await expect(page.getByRole('heading',{name:'Client tool requests',exact:true})).toBeVisible();
  await expect(page.locator('#main')).toHaveCSS('max-width','none');
  const main=await page.locator('#main').boundingBox(),workspace=await page.locator('.workspace').boundingBox();expect(main!.width).toBeCloseTo(workspace!.width,0);
  await page.getByRole('button',{name:'View response canvas-request'}).click();await expect(page.getByRole('region',{name:'Client response'})).toContainText('<script>display this as text</script>');
  await expect(page.getByRole('region',{name:'Client response'}).locator('script')).toHaveCount(0);
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('real Quickstart password, non-HA status, protected compute and vault names',async({page,request})=>{
  test.skip(!process.env.QUICKSTART_PASSWORD,'Requires actual composed Quickstart fixture');
  await page.goto('/console/');await expect(page.getByRole('status')).toContainText('Non-HA');
  const configuration=await request.get('/api/public/v1/clients/configuration');expect(configuration.status()).toBe(200);
  await expect(page.getByLabel('Gateway URL',{exact:true})).toHaveValue((await configuration.json()).serverUrl);
  await expect(page.getByRole('button',{name:'Copy Gateway URL'})).toBeVisible();
  await page.getByLabel('Password',{exact:true}).fill(process.env.QUICKSTART_PASSWORD!);
  await page.getByRole('button',{name:'Connect to workspace'}).click();
  await expect(page.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();
  await page.locator('nav summary').filter({hasText:'Devices'}).click();
  await page.getByRole('link',{name:'Clients',exact:true}).click();
  await expect(page.getByRole('columnheader',{name:'Connection',exact:true})).toBeVisible();
  const checkedInClient=page.getByRole('row').filter({hasText:'quickstart-test-device'});
  await expect(checkedInClient.locator('.client-connection.online')).toHaveText('Connected');
  await expect(page.getByRole('columnheader',{name:'Device name',exact:true})).toBeVisible();
  await expect(page.getByRole('columnheader',{name:'Owner',exact:true})).toBeVisible();
  for(const id of ['local-builtins','local-hotfolder','local-rest-forwarding']){
    const row=page.getByRole('row').filter({hasText:id});await expect(row.locator('.client-connection.online')).toHaveText('Available');await expect(row).toContainText('Local tool requester');
  }
  await page.getByRole('button',{name:'Details for local-hotfolder',exact:true}).focus();await expect(page.getByRole('tooltip')).toContainText('HotFolder');
  await page.keyboard.press('Escape');await expect(page.getByRole('tooltip')).toHaveCount(0);
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  await page.locator('nav summary').filter({hasText:'Tools'}).click();
  await page.getByRole('link',{name:'Built-in tools and vault'}).click();
  await page.getByRole('button',{name:'Run protected tool'}).click();
  await expect(page.getByRole('region',{name:'Tool result'})).toContainText('14');
  await expect(page.getByText('Stored references: demo/token')).toBeVisible();
  const result=await new AxeBuilder({page}).analyze();expect(result.violations).toEqual([]);
  expect(await page.evaluate(()=>Object.keys(localStorage).filter(key=>!key.startsWith('toolgate.')).length+sessionStorage.length)).toBe(0);
  await page.getByRole('button',{name:'Disconnect'}).click();await expect(page.getByLabel('Password',{exact:true})).toHaveValue('');
});

test('pending devices can be reviewed and approved until a local date and time',async({page})=>{
  test.skip(!process.env.QUICKSTART_PASSWORD,'Requires actual composed Quickstart fixture');
  const devices=['device-one','device-two'].map((deviceId,index)=>({enrollmentId:`enrollment-${index}`,deviceId,userCode:String(index+1).repeat(16),platform:'WINDOWS',keyFingerprint:'a'.repeat(64),state:'PENDING',expiresAtUnixMs:Date.now()+600000}));
  let pending=devices;
  let decision:Record<string,unknown>|undefined;
  await page.route('**/api/control/v1/endpoint/enrollments',route=>route.fulfill({json:{items:pending}}));
  await page.route('**/api/control/v1/endpoint/enrollments/review?*',route=>route.fulfill({json:devices[1]}));
  await page.route('**/api/control/v1/endpoint/enrollments/decision',route=>{
    decision=route.request().postDataJSON();pending=[devices[0]];
    return route.fulfill({json:{...devices[1],state:'APPROVED',connectionExpiresAtUnixMs:decision?.connectionExpiresAtUnixMs}});
  });
  await page.route('**/api/control/v1/endpoint/devices/device-two',route=>route.fulfill({json:{deviceId:'device-two',keyFingerprint:'a'.repeat(64),state:'ACTIVE',reportSequence:1,lastSeenUnixMs:Date.now()}}));
  await page.goto('/console/');await page.getByLabel('Password',{exact:true}).fill(process.env.QUICKSTART_PASSWORD!);
  await page.getByRole('button',{name:'Connect to workspace'}).click();await expect(page.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();
  await page.goto('/console/#enroll');await expect(page.getByRole('button',{name:'Review device-one'})).toBeVisible();
  await page.getByRole('button',{name:'Review device-two'}).click();
  await page.getByLabel('Allow connection until').fill('2030-01-01T18:30');
  await expect(page.getByRole('button',{name:'Enroll device',exact:true})).toBeDisabled();
  await page.getByRole('checkbox').check();
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  await page.getByRole('button',{name:'Enroll device',exact:true}).click();
  await expect(page.getByText('Connection allowed until',{exact:true})).toBeVisible();
  expect(decision).toMatchObject({userCode:devices[1].userCode,keyFingerprint:devices[1].keyFingerprint,choice:'APPROVE'});
  expect(decision?.connectionExpiresAtUnixMs).toBe(await page.evaluate(()=>new Date('2030-01-01T18:30').getTime()));
  await expect(page.getByRole('button',{name:'Review device-two'})).toHaveCount(0);
  await expect(page.getByText('Connected: this device has checked in with ToolGate.')).toBeVisible();
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
