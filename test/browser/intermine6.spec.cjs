const { test, expect } = require('@playwright/test');

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
    params: { format: 'json', query: JSON.stringify({
      model: { name: 'testmodel' },
      select: ['Employee.id'],
      where: [{ path: 'Employee.name', op: '=', value: 'Neil Godwin' }],
    }) },
  });
  expect(employee.ok()).toBeTruthy();
  const { results } = await employee.json();
  expect(results).toHaveLength(1);
  await page.goto(`${root}/intermine6/report/Employee/${results[0][0]}`);
  await expect(page.getByRole('main').getByRole('heading', { name: 'Neil GodwinEmployee', exact: true, level: 1 })).toBeVisible();
  expect(errors).toEqual([]);
});
