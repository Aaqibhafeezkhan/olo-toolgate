// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test';

test('explicit local password-free mode enters the console and still uses protected tools', async ({page}) => {
  test.skip(process.env.UI_PASSWORDLESS_TEST !== '1', 'Requires explicit password-free Quickstart');
  await page.goto('/console/');
  await expect(page.getByRole('heading',{name:'Your organization, at a glance'})).toBeVisible();
  await expect(page.getByLabel('Password',{exact:true})).toHaveCount(0);
  for (const path of ['/api/control/v1/users/admin', '/api/control/v1/builder/drafts', '/api/control/v1/builder/builds', '/api/control/v1/fleet/releases', '/api/control/v1/fleet/rollouts']) {
    const result = await page.evaluate(async (path) => {
      const login = await fetch('/api/quickstart/v1/login', {method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());
      const response = await fetch(path,{headers:{Authorization:`Bearer ${login.accessToken}`}});
      return {status:response.status,body:await response.json()};
    },path);
    expect(result.status, path).toBe(200);
    if (path.endsWith('/admin')) expect(result.body).toMatchObject({id:'admin',enabled:true});
  }
  await page.locator('nav summary').filter({hasText:'Tools'}).click();
  await page.getByRole('link',{name:'Tool builder',exact:true}).click();
  await expect(page.getByRole('button',{name:'Save and scan draft'})).toBeEnabled();
  await page.getByLabel('Approved digest-pinned runtime image').fill('registry.example.invalid/test/python@sha256:'+'a'.repeat(64));
  await page.getByLabel('Exact runtime version').fill('3.12.3');
  await page.getByLabel('Package ID',{exact:true}).fill('smoke-'+Date.now());
  await page.getByRole('button',{name:'Save and scan draft'}).click();
  await expect(page.getByRole('heading',{name:'Test, package and deploy'})).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.locator('nav summary').filter({hasText:'Devices'}).click();
  await page.getByRole('link',{name:'Packages',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Publish a signed release'})).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.getByRole('link',{name:'Built-in tools and vault'}).click();
  await expect(page.getByRole('button',{name:'Run protected tool'})).toBeEnabled();
  await page.getByLabel('Arguments (JSON object)').fill('{"expression":"2+3*4"}');
  await page.getByRole('button',{name:'Run protected tool'}).click();
  await expect(page.getByRole('region',{name:'Tool result'})).toContainText('14');
});
