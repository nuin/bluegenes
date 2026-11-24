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
    const preference = `retained-${randomUUID()}`;
    await api('user/preferences', identity.token, {
      method: 'POST', body: new URLSearchParams({ aka: preference }),
    });
    await withAccount(async other => {
      assert.equal((await api('lists', other.token)).lists.some(list => list.name === name), false);
      assert.notEqual((await api('user/preferences', other.token)).preferences.aka, preference);
    });
    const secondSession = await api('session');
    const secondLogin = await auth('login', secondSession.token, credentials);
    await auth('logout', identity.token);
    const secondExpired = await fetch(`${mine}/service/user`, {
      headers: { Authorization: `Token ${secondLogin.identity.token}` },
      signal: AbortSignal.timeout(15000),
    });
    assert.equal(secondExpired.status, 401, 'Logout must also revoke the other account token');
    const expired = await fetch(`${mine}/service/user?${new URLSearchParams({ token: identity.token })}`,
      { signal: AbortSignal.timeout(15000) });
    assert.equal(expired.status, 401, 'Logout must invalidate the server-side API token');
    const session = await api('session');
    assert.equal((await api('lists', session.token)).lists.some(list => list.name === name), false);
    const loggedIn = await auth('login', session.token, credentials);
    const retained = (await api('lists', loggedIn.identity.token)).lists.find(list => list.name === name);
    assert.equal(retained.size, 1, 'The private list must survive logout and login');
    assert.equal((await api('user/preferences', loggedIn.identity.token)).preferences.aka,
      preference, 'Account preferences must survive logout and login');
  });
});
