const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');
const { api, readResponse, withAccount } = require('../fixtures/intermine.cjs');
const bluegenes = process.env.BLUEGENES_URL.replace(/\/$/, '');
const mine = process.env.TESTMODEL_URL.replace(/\/$/, '');

test('InterMine 6 metadata and the production BlueGenes bundle are served', async () => {
  const version = await api('version/intermine');
  assert.match(version.version, /^6\./);
  for (const path of ['model', 'templates', 'lists', 'summaryfields', 'web-properties']) {
    assert.ok(await api(path, undefined, {}, { format: 'json' }), path);
  }
  const html = await readResponse(await fetch(`${bluegenes}/intermine6`, {
    signal: AbortSignal.timeout(15000),
  }), 'BlueGenes index');
  const bundle = html.match(/src="([^"]*\/app-[^"/]+\.js)"/);
  assert.ok(bundle, 'The production page must reference a fingerprinted application bundle');
  const response = await fetch(new URL(bundle[1], bluegenes), {
    headers: { 'Accept-Encoding': 'gzip' }, signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, 200);
  assert.ok((await response.text()).length > 100000, 'The bundle must contain compiled code');
});

test('BlueGenes registration, login, lists and logout work with InterMine 6', async () => {
  await withAccount(async (identity, credentials, auth) => {
    const name = `bluegenes-list-${randomUUID()}`;
    await api('lists', identity.token, {
      method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '"David Brent"',
    }, { name, type: 'Employee' });
    const created = (await api('lists', identity.token)).lists.find(list => list.name === name);
    assert.equal(created.size, 1);
    await auth('logout', identity.token);
    const expired = await fetch(`${mine}/service/user?${new URLSearchParams({ token: identity.token })}`,
      { signal: AbortSignal.timeout(15000) });
    assert.equal(expired.status, 401, 'Logout must invalidate the server-side API token');
    const session = await api('session');
    const loggedIn = await auth('login', session.token, credentials);
    const retained = (await api('lists', loggedIn.identity.token)).lists.find(list => list.name === name);
    assert.equal(retained.size, 1, 'The private list must survive logout and login');
  });
});
