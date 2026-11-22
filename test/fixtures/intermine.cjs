const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');

assert.ok(process.env.TESTMODEL_URL, 'TESTMODEL_URL must identify a disposable InterMine 6 fixture');
assert.ok(process.env.BLUEGENES_URL, 'BLUEGENES_URL must include the BlueGenes deployment prefix');
const mine = process.env.TESTMODEL_URL.replace(/\/$/, '');
const bluegenes = process.env.BLUEGENES_URL.replace(/\/$/, '');

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

function authClient() {
  let cookie = '';
  return async function auth(action, token, credentials = {}) {
    const response = await fetch(`${bluegenes}/api/auth/${action}`, {
      method: 'POST', signal: AbortSignal.timeout(15000),
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', Cookie: cookie },
      body: JSON.stringify({ ...credentials, service: { root: mine, token } }),
    });
    const session = response.headers.getSetCookie().find(value => value.startsWith('ring-session='));
    if (session) cookie = session.split(';')[0];
    return readResponse(response, action);
  };
}

async function withAccount(check) {
  const auth = authClient();
  const credentials = { username: `bluegenes-java21-${randomUUID()}`, password: randomUUID() };
  const session = await api('session');
  const result = await auth('register', session.token, credentials);
  try {
    assert.ok(result.identity?.token, 'Registration must produce an authenticated identity');
    await check(result.identity, credentials, auth);
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

module.exports = { api, readResponse, withAccount };
