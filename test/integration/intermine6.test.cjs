const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');

assert.ok(process.env.TESTMODEL_URL, 'TESTMODEL_URL must identify a disposable InterMine 6 fixture');
assert.ok(process.env.BLUEGENES_URL, 'BLUEGENES_URL must include the BlueGenes deployment prefix');
const mine = process.env.TESTMODEL_URL.replace(/\/$/, '');
const bluegenes = process.env.BLUEGENES_URL.replace(/\/$/, '');
let cookie = '';

async function readResponse(response, path) {
  assert.ok(response.ok, `${path} returned HTTP ${response.status}`);
  return response.headers.get('content-type')?.includes('json')
    ? response.json() : response.text();
}

async function api(path, token, options = {}, params = {}) {
  const url = new URL(`${mine}/service/${path}`);
  url.search = new URLSearchParams({ format: 'json', ...params, ...(token ? { token } : {}) });
  return readResponse(await fetch(url, {
    ...options, signal: AbortSignal.timeout(15000),
  }), path);
}

async function auth(action, token, credentials = {}) {
  const response = await fetch(`${bluegenes}/api/auth/${action}`, {
    method: 'POST', signal: AbortSignal.timeout(15000),
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', Cookie: cookie },
    body: JSON.stringify({ ...credentials, service: { root: mine, token } }),
  });
  const session = response.headers.getSetCookie().find(value => value.startsWith('ring-session='));
  if (session) cookie = session.split(';')[0];
  return readResponse(response, action);
}

async function withAccount(check) {
  const credentials = { username: `bluegenes-java21-${randomUUID()}`, password: randomUUID() };
  const session = await api('session');
  const result = await auth('register', session.token, credentials);
  try {
    assert.ok(result.identity?.token, 'Registration must produce an authenticated identity');
    await check(result.identity, credentials);
  } finally {
    const fresh = await api('session');
    const login = await auth('login', fresh.token, credentials);
    const token = login.identity.token;
    const deletion = await api('user/deregistration', token, { method: 'POST' });
    await api('user', token, { method: 'DELETE' }, {
      deregistrationToken: deletion.token.uuid, format: 'xml',
    });
    await auth('logout', token);
  }
}

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
  await withAccount(async (identity, credentials) => {
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
