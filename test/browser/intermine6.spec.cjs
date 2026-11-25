const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');
const { api, withAccount } = require('../fixtures/intermine.cjs');

if (!process.env.BLUEGENES_URL) throw new Error('BLUEGENES_URL must include the deployment prefix');
if (!process.env.TESTMODEL_URL) throw new Error('TESTMODEL_URL must identify the paired InterMine fixture');
const root = process.env.BLUEGENES_URL.replace(/\/$/, '');
const mine = process.env.TESTMODEL_URL.replace(/\/$/, '');

test('production UI boots and navigates through the configured mine', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${root}/intermine6`);
  const navigation = page.getByRole('navigation');
  await expect(navigation.getByRole('button', { name: 'Login', exact: true })).toBeVisible();
  await navigation.getByRole('link', { name: 'Lists', exact: true }).click();
  await expect(page).toHaveURL(`${root}/intermine6/lists`);
  await expect(page.getByRole('main').getByRole('heading', { name: 'Lists', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Everyones-Favourite-Employees', exact: true }).click();
  await expect(page.getByRole('main').getByRole('heading', { name: 'Query Results', exact: true })).toBeVisible();
  await expect(page.getByRole('main')).toContainText('Date unavailable');
  await navigation.getByRole('link', { name: 'Templates', exact: true }).click();
  await expect(page).toHaveURL(`${root}/intermine6/templates`);
  await expect(page.getByRole('main')).toContainText('Employee');
  await navigation.getByRole('link', { name: 'Query Builder', exact: true }).click();
  await expect(page).toHaveURL(`${root}/intermine6/querybuilder`);
  await expect(page.getByRole('main')).toContainText('Employee');
  const employee = await page.request.get(`${mine}/service/query/results`, {
    params: { format: 'json', query: '<query model="testmodel" view="Employee.id">'
      + '<constraint path="Employee.name" op="=" value="Neil Godwin"/></query>' },
  });
  expect(employee.status()).toBe(200);
  const { results } = await employee.json();
  expect(results).toHaveLength(1);
  await page.goto(`${root}/intermine6/report/Employee/${results[0][0]}`);
  await expect(page.getByRole('main').getByRole('heading', { name: 'Neil GodwinEmployee', exact: true, level: 1 })).toBeVisible();
  expect(errors).toEqual([]);
});

test('browser login reveals private lists and logout removes access', async ({ page }) => {
  await withAccount(async (identity, credentials) => {
    const name = `browser-private-${randomUUID()} / β &`;
    await api('lists', identity.token, {
      method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '"David Brent"',
    }, { name, type: 'Employee' });
    await page.goto(`${root}/intermine6/lists`);
    const navigation = page.getByRole('navigation');
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(0);
    await navigation.getByRole('button', { name: 'Login', exact: true }).click();
    const form = page.locator('form.login-form');
    await form.locator('#email').fill(credentials.username);
    await form.locator('input[type="password"]').fill(credentials.password);
    await form.getByRole('button', { name: 'Login', exact: true }).click();
    await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
    await page.goto(`${mine}/bagDetails.do?${new URLSearchParams({ bagName: name })}`);
    await expect(page).toHaveURL(`${root}/intermine6/results?title=${encodeURIComponent(name)}`);
    await expect(page.getByRole('main')).toContainText('David Brent');
    await page.reload();
    await expect(page.getByRole('main')).toContainText('David Brent');
    await navigation.getByRole('link', { name: 'Lists', exact: true }).click();
    await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
    await navigation.locator('.logon.success .dropdown-toggle').click();
    await navigation.getByText('Logout', { exact: true }).click();
    await expect(navigation.getByRole('button', { name: 'Login', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(0);
    await page.reload();
    await expect(navigation.getByRole('button', { name: 'Login', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(0);
  });
});


test('legacy browser entry links reach the primary UI and retain API access', async ({ page }) => {
  await page.goto(`${mine}/`);
  await expect(page).toHaveURL(`${root}/intermine6`);
  await expect(page.getByRole('navigation')).toBeVisible();
  const version = await page.request.get(`${mine}/service/version/ws`);
  expect(version.ok()).toBe(true);
  const legacy = await page.request.get(`${mine}/begin.do?ui=legacy`, { maxRedirects: 0 });
  expect(legacy.status()).toBe(200);
  await page.goto(`${mine}/bagDetails.do?bagName=Everyones-Favourite-Employees`);
  await expect(page).toHaveURL(`${root}/intermine6/results/Everyones-Favourite-Employees`);
  const main = page.getByRole('main');
  await expect(main).toContainText('Neil Godwin');
  await main.locator('td a').filter({ hasText: 'Neil Godwin' }).first().hover();
  const reportLink = main.locator('a[href*="/report/Employee/"]').first();
  await expect(reportLink).toBeVisible();
  const reportPath = await reportLink.getAttribute('href');
  const id = reportPath.split('/').pop();
  await page.goto(`${mine}/report.do?id=${id}`);
  await expect(page).toHaveURL(`${root}/intermine6/report/Employee/${id}`);
  await expect(main.getByRole('heading', { level: 1 })).toContainText('Employee');
  await page.goto(`${mine}/templates.do`);
  await expect(main).toContainText('Employee');
  const template = main.locator('.template[id]').first();
  await expect(template).toBeVisible();
  const templateName = await template.getAttribute('id');
  await page.goto(`${mine}/template.do?${new URLSearchParams({ name: templateName })}`);
  await expect(page).toHaveURL(`${root}/intermine6/templates/${encodeURIComponent(templateName)}`);
  await expect(main.locator('.template.selected .view-results')).toBeVisible();
});

test('linked queries survive redirects and reload with their constraints and ordering', async ({ page }) => {
  const query = '<query model="testmodel" name="Linked employee" view="Employee.name" sortOrder="Employee.name DESC">'
    + '<constraint path="Employee.name" op="=" value="Neil Godwin"/></query>';
  await page.goto(`${mine}/loadQuery.do?${new URLSearchParams({ method: 'xml', query })}`);
  await expect(page).toHaveURL(new RegExp('/querybuilder\\?'));
  await expect(page.getByRole('main')).toContainText('Neil Godwin');
  await page.reload();
  await expect(page.getByRole('main')).toContainText('Neil Godwin');
  const queries = [];
  page.on('request', request => {
    if (request.url().includes('/query/results')) queries.push(request.url() + (request.postData() || ''));
  });
  await page.goto(`${mine}/loadQuery.do?${new URLSearchParams({ method: 'xml', query, skipBuilder: 'true' })}`);
  await expect(page.getByRole('main').getByRole('heading', { name: 'Query Results', exact: true })).toBeVisible();
  await expect(page.getByRole('main')).toContainText('Neil Godwin');
  expect(queries.some(request => decodeURIComponent(request).includes('DESC'))).toBe(true);
  await page.goto(`${root}/intermine6/querybuilder?query=${encodeURIComponent('<invalid/>')}&run=true`);
  await expect(page.getByRole('heading', { name: 'Query link unavailable', exact: true })).toBeVisible();
  await expect(page.getByRole('main')).not.toContainText('Neil Godwin');
});

test('generic mine explains direct Regions links without biological queries', async ({ page }) => {
  const biological = [];
  page.on('request', request => {
    const value = decodeURIComponent(request.url() + (request.postData() || ''));
    if (/query\/results/.test(value) && /Organism|SequenceFeature|Chromosome/.test(value)) biological.push(value);
  });
  await page.goto(`${root}/intermine6/regions`);
  await expect(page.getByRole('heading', { name: 'Regions unavailable', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation').getByRole('link', { name: 'Regions', exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Open query builder', exact: true }).click();
  await expect(page.getByRole('main')).toContainText('Employee');
  expect(biological).toEqual([]);
});


test('unsupported API version stays visible and can be retried', async ({ page }) => {
  await page.route(/\/service\/version(?:\?.*)?$/, async route => {
    const response = await route.fetch();
    await route.fulfill({ response, body: "26\n", contentType: "text/plain" });
  });
  await page.goto(`${root}/intermine6`);
  await expect(page.getByRole('main')).toContainText('BlueGenes requires API 27 or later.');
  await expect(page.getByRole('heading', { name: 'Mine unavailable', exact: true })).toBeVisible();
  await page.unroute(/\/service\/version(?:\?.*)?$/);
  await page.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Mine unavailable', exact: true })).toHaveCount(0);
  await page.getByRole('navigation').getByRole('link', { name: 'Lists', exact: true }).click();
  await expect(page.getByRole('main').getByRole('heading', { name: 'Lists', exact: true })).toBeVisible();
});
