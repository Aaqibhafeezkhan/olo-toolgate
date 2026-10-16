// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {test,expect,type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {mkdirSync} from 'node:fs';
import {managementCatalog} from '../../src/managementPermissions.generated';

const allPermissions=managementCatalog.resources.flatMap(resource=>resource.actions.map(action=>`${resource.id}.${action}`));
test.skip(process.env.UI_TEST_EXPERIENCE!=='true','Requires the inert production UI fixture server');
async function fixture(page:Page,count=2){
  const users=Array.from({length:count},(_,index)=>({id:`user-${index}`,name:index===0?'Zulu User':index===1?'Alpha User':`User ${index}`,enabled:index%2===0,revision:1}));let pending=true;
  await page.route('**/api/control/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),path=url.pathname;
    const reply=(body:unknown,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
    if(path.endsWith('/admin-session'))return reply({role:'SUPER_ADMIN',permissions:allPermissions});
    if(path.endsWith('/users/signed-user'))return reply({id:'signed-user',name:'Test Administrator',enabled:true,revision:1});
    if(path.includes('/users/')&&['PUT','DELETE'].includes(request.method())){
      const id=decodeURIComponent(path.split('/').at(-1)!);const index=users.findIndex(user=>user.id===id);
      if(request.method()==='DELETE'){users.splice(index,1);return route.fulfill({status:204});}
      Object.assign(users[index],request.postDataJSON(),{revision:users[index].revision+1});return reply(users[index]);
    }
    if(path.endsWith('/users'))return reply({items:users});
    if(path.endsWith('/approvals'))return reply({items:pending?[{id:'test-approval',state:'PENDING',expiresAtUnixMs:Date.now()+60000}]:[]});
    return reply({items:[]});
  });
  await page.goto('/console/');
  const value=`fixture.${Buffer.from(JSON.stringify({iss:'ui-test',tenant_id:'fixture',sub:'signed-user',user_id:'signed-user'})).toString('base64url')}.test-only`;
  await page.getByLabel('Access token').fill(value);await page.getByRole('button',{name:'Connect to workspace'}).click();await expect(page.getByRole('navigation')).toBeVisible();
  return {clearPending:()=>{pending=false;},value};
}
async function users(page:Page){await page.locator('nav details').filter({has:page.locator('summary',{hasText:'Users'})}).locator('summary').click();await page.getByRole('navigation').getByRole('link',{name:'Users',exact:true}).click();await expect(page.getByRole('heading',{name:'Users',exact:true})).toBeVisible();}
test('fresh navigation, independent scrolling, sidebar user/bell and page restoration',async({page})=>{
  const f=await fixture(page,45);await expect(page.locator('nav details[open]')).toHaveCount(0);await expect(page.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();await expect(page.locator('nav details').last().locator('summary')).toHaveText('Administration');
  const name=await page.getByText('Test Administrator',{exact:true}).boundingBox(),bell=await page.getByRole('button',{name:'Pending approvals'}).boundingBox();expect(name&&bell).toBeTruthy();expect(Math.abs(name!.y+name!.height/2-bell!.y-bell!.height/2)).toBeLessThan(4);expect(bell!.x).toBeGreaterThan(name!.x);
  await users(page);for(const summary of await page.locator('nav summary').all()){if(await summary.locator('..').getAttribute('open')===null)await summary.click();}
  const sizes=await page.evaluate(()=>{const nav=document.querySelector('.navigation-scroll')!,workspace=document.querySelector('.workspace')!;return {navOverflow:nav.scrollHeight>nav.clientHeight,contentOverflow:workspace.scrollHeight>workspace.clientHeight};});expect(sizes).toEqual({navOverflow:true,contentOverflow:true});
  await page.evaluate(()=>{document.querySelector('.navigation-scroll')!.scrollTop=0;document.querySelector('.workspace')!.scrollTop=500;});expect(await page.locator('.navigation-scroll').evaluate(element=>element.scrollTop)).toBe(0);
  await page.evaluate(()=>{document.querySelector('.navigation-scroll')!.scrollTop=100;});expect(await page.locator('.workspace').evaluate(element=>element.scrollTop)).toBe(500);
  await expect(page.locator('nav details').last().locator('summary')).toBeInViewport();
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
  mkdirSync('../../build/ui',{recursive:true});await page.screenshot({path:'../../build/ui/rc-experience-desktop.png'});
  await page.getByRole('button',{name:'Disconnect'}).click();await page.getByLabel('Access token').fill(f.value);await page.getByRole('button',{name:'Connect to workspace'}).click();await expect(page.getByRole('heading',{name:'Users',exact:true})).toBeVisible();
  f.clearPending();await page.evaluate(()=>window.dispatchEvent(new Event('toolgate:approval-change')));await expect(page.getByRole('button',{name:'No pending approvals'})).toBeVisible();
});
test('header sorting, status switches, selection and confirmed deletion',async({page})=>{
  await fixture(page);await users(page);await page.getByRole('button',{name:'Sort by Name'}).click();await expect(page.getByRole('row').nth(1)).toContainText('Alpha User');
  const toggle=page.getByRole('switch',{name:'Disable user-0'});await expect(toggle).toHaveAttribute('aria-checked','true');await toggle.click();await expect(page.getByRole('switch',{name:'Enable user-0'})).toHaveAttribute('aria-checked','false');
  await expect(page.getByRole('button',{name:'Edit user-0',exact:true})).toHaveAttribute('title','Edit user-0');
  await page.getByRole('checkbox',{name:'Select all rows on this page'}).check();await page.getByRole('button',{name:'Enable selected'}).click();await expect(page.getByRole('switch',{name:'Disable user-0'})).toBeVisible();await expect(page.getByRole('switch',{name:'Disable user-1'})).toBeVisible();
  await page.getByRole('checkbox',{name:'Select all rows on this page'}).check();await page.getByRole('button',{name:'Delete selected'}).click();await expect(page.getByRole('heading',{name:'Delete 2 records?'})).toBeVisible();await expect(page.getByRole('switch')).toHaveCount(2);await page.getByRole('button',{name:'Confirm delete'}).click();await expect(page.getByRole('heading',{name:'No users on this page'})).toBeVisible();
});
test('narrow viewport preserves independent panels and usable icon navigation',async({page})=>{
  await page.setViewportSize({width:390,height:844});await fixture(page);await users(page);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByRole('button',{name:'Collapse navigation',exact:true}).click();await expect(page.getByRole('button',{name:'Administration',exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);await page.screenshot({path:'../../build/ui/rc-experience-mobile.png'});
});
