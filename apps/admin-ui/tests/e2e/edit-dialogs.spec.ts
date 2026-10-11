// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect,type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {mkdirSync} from 'node:fs';

/** Inert API responses isolate layout, editing and native modal behavior. */
async function fixture(page:Page){
  const users=Array.from({length:45},(_,index)=>({id:`user-${index}`,name:`User ${index}`,enabled:true,revision:1}));
  const team={id:'team-1',name:'Finance team',enabled:true,revision:1,userIds:[],roleIds:[]};
  const device={id:'device-1',name:'Office client',enabled:true,revision:1,ownerUserId:'user-0',groupIds:['default-devices']};
  await page.route('**/api/control/v1/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    const reply=(body:unknown)=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    if(path.endsWith('/admin-session'))return reply({role:'SUPER_ADMIN'});
    if(path.endsWith('/users/signed-user'))return reply({id:'signed-user',name:'Test Administrator',enabled:true,revision:1});
    if(path.endsWith('/endpoint/devices'))return reply({items:[{deviceId:device.id,systemExecutor:false,directoryDevice:device}]});
    if(path.endsWith('/groups'))return reply({entityType:path.includes('/users/')?'USER':'DEVICE',entityId:path.split('/').at(-2),groupIds:['default-devices'],revision:1});
    if(path.includes('/effective-access'))return reply({entityId:path.split('/').at(-2),kind:'USER',directoryRevision:1,authorizationEpoch:1,memberships:[],bindings:[],managementRoles:[]});
    if(path.endsWith('/users'))return reply({items:users});
    if(path.endsWith('/teams'))return reply({items:[team]});
    return reply({items:[]});
  });
  await page.goto('/console/');await page.getByLabel('Access token').fill('fixture.test-only');await page.getByRole('button',{name:'Connect to workspace'}).click();
  await expect(page.getByRole('navigation')).toBeVisible();
}
async function navigate(page:Page,route:string,title:string){
  await page.evaluate(hash=>{location.hash=hash;},`#${route}`);await expect(page.getByRole('main').getByRole('heading',{name:title,exact:true})).toBeVisible();
}
async function fillsWorkspace(page:Page){
  const size=await page.locator('main').evaluate(main=>{const parent=main.parentElement!,style=getComputedStyle(main);return {width:main.getBoundingClientRect().width,parent:parent.clientWidth,padding:style.paddingLeft};});
  expect(Math.abs(size.width-size.parent)).toBeLessThan(1);expect(size.padding).toBe('24px');
  for(const panel of await page.locator('main > .guidance').all()){
    const size=await panel.evaluate(element=>{const main=element.parentElement!,style=getComputedStyle(main);return {width:element.getBoundingClientRect().width,available:main.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight),padding:getComputedStyle(element).paddingLeft};});
    expect(Math.abs(size.width-size.available)).toBeLessThan(1);expect(size.padding).toBe('24px');
  }
}

test('wide screens use the full workspace and editing belongs to the action icon',async({page})=>{
  await page.setViewportSize({width:2400,height:900});await fixture(page);
  for(const [route,title] of [['overview','Your organization, at a glance'],['tools','Tools'],['builder','Custom tool builder'],['users','Users']]){await navigate(page,route,title);await fillsWorkspace(page);}
  expect((await page.getByRole('main').boundingBox())!.width).toBeGreaterThan(1400);
  await page.getByRole('row').filter({has:page.getByText('User 0',{exact:true})}).getByText('User 0',{exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button',{name:'User 0',exact:true})).toHaveCount(0);
  const edit=page.getByRole('button',{name:'Edit user-0',exact:true});await edit.click();
  const dialog=page.getByRole('dialog',{name:'Edit user',exact:true});await expect(dialog).toBeVisible();await expect(page.locator('.workspace .editor-dialog')).toHaveCount(0);
  expect((await dialog.boundingBox())!.width).toBe(2352);await expect(dialog.getByLabel('Display name')).toHaveValue('User 0');
  const available=await dialog.locator('.editor-dialog-body').evaluate(body=>{const style=getComputedStyle(body);return body.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight);});expect((await dialog.locator('.user-form').boundingBox())!.width).toBe(available);
  await dialog.getByLabel('Display name').fill('Unsaved change');await dialog.getByRole('button',{name:'Close editor'}).click();await expect(dialog).toHaveCount(0);await expect(edit).toBeFocused();
  await edit.click();await expect(dialog.getByLabel('Display name')).toHaveValue('User 0');await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(edit).toBeFocused();
});

test('long editors scroll inside the popup and nested picker Escape preserves the parent',async({page})=>{
  await page.setViewportSize({width:1440,height:700});await fixture(page);await navigate(page,'users','Users');
  const edit=page.getByRole('button',{name:'Edit user-18',exact:true});await edit.scrollIntoViewIfNeeded();const parentScroll=await page.locator('.workspace').evaluate(element=>element.scrollTop);expect(parentScroll).toBeGreaterThan(0);await edit.click();
  const dialog=page.getByRole('dialog',{name:'Edit user',exact:true});await expect(dialog).toBeVisible();await page.keyboard.press('Escape');await expect(edit).toBeFocused();expect(await page.locator('.workspace').evaluate(element=>element.scrollTop)).toBe(parentScroll);
  await navigate(page,'policies','Policies');await page.getByRole('button',{name:'Add policy',exact:true}).click();
  const policy=page.getByRole('dialog',{name:'Add policy',exact:true}),body=policy.locator('.editor-dialog-body'),close=policy.getByRole('button',{name:'Close editor'});
  await expect(policy).toBeVisible();expect(await body.evaluate(element=>element.scrollHeight>element.clientHeight)).toBe(true);
  const headerY=(await close.boundingBox())!.y;await body.evaluate(element=>{element.scrollTop=element.scrollHeight;});await expect(close).toBeInViewport();expect((await close.boundingBox())!.y).toBe(headerY);
  const choose=policy.getByRole('button',{name:'Choose tool groups',exact:true});await choose.click();const picker=page.getByRole('dialog',{name:'Tool groups',exact:true});await expect(picker).toBeVisible();await page.keyboard.press('Escape');await expect(picker).toHaveCount(0);await expect(policy).toBeVisible();await expect(choose).toBeFocused();
  await close.focus();await page.keyboard.press('Shift+Tab');expect(await policy.evaluate(element=>element.contains(document.activeElement))).toBe(true);
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);mkdirSync('../../build/ui',{recursive:true});await page.screenshot({path:'../../build/ui/edit-dialog-desktop.png'});
  await page.keyboard.press('Escape');await expect(policy).toHaveCount(0);await expect(page.getByRole('button',{name:'Add policy',exact:true})).toBeFocused();
});

test('mobile popup fits the viewport and client edits open only from the icon',async({page})=>{
  await page.setViewportSize({width:390,height:844});await fixture(page);await navigate(page,'devices','Clients');
  await page.getByRole('button',{name:'Collapse navigation',exact:true}).click();await page.getByText('Office client',{exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button',{name:'Edit device-1',exact:true}).click();const client=page.getByRole('dialog',{name:'Edit device',exact:true});await expect(client).toBeVisible();await client.getByRole('button',{name:'Close editor'}).click();
  await navigate(page,'policies','Policies');await page.getByRole('button',{name:'Add policy',exact:true}).click();const dialog=page.getByRole('dialog',{name:'Add policy',exact:true});await expect(dialog).toBeVisible();
  const box=(await dialog.boundingBox())!;expect(box.x).toBeGreaterThanOrEqual(12);expect(box.width).toBe(366);expect(box.y+box.height).toBeLessThanOrEqual(832);
  const body=dialog.locator('.editor-dialog-body');expect(await body.evaluate(element=>element.scrollWidth<=element.clientWidth)).toBe(true);await body.evaluate(element=>{element.scrollTop=element.scrollHeight;});await expect(dialog.getByRole('button',{name:'Close editor'})).toBeInViewport();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  mkdirSync('../../build/ui',{recursive:true});await page.screenshot({path:'../../build/ui/edit-dialog-mobile.png'});await dialog.getByRole('button',{name:'Close editor'}).click();await expect(page.getByRole('button',{name:'Add policy',exact:true})).toBeFocused();
});
