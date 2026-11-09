const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const source = readFileSync('src/main/resources/swagger/csrf-interceptor.js', 'utf8');

function interceptor(fetch) {
  return vm.runInNewContext('(' + source + ')', {
    URL, fetch,
    window: { location: { origin: 'http://localhost:8080',
      href: 'http://localhost:8080/app/api/swagger-ui/index.html',
      pathname: '/app/api/swagger-ui/index.html' } }
  });
}

test('write requests use the masked token and context path, never document.cookie', async () => {
  const calls = [];
  const apply = interceptor(async (url, options) => {
    calls.push({ url, options });
    return { ok: true, json: async () => ({ token: 'masked-token', header_name: 'X-XSRF-TOKEN' }) };
  });
  const request = await apply({ url: '/app/api/v1/auth/login', method: 'POST', headers: {} });
  assert.equal(calls[0].url, '/app/api/v1/auth/csrf');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(request.headers['X-XSRF-TOKEN'], 'masked-token');
  assert.equal(request.credentials, 'same-origin');
});

test('reads do not fetch CSRF and external targets are rejected', async () => {
  let calls = 0;
  const apply = interceptor(async () => { calls++; });
  await apply({ url: '/app/api/v1/users/me', method: 'GET' });
  await assert.rejects(apply({ url: 'https://external.example/api/users', method: 'POST' }));
  assert.equal(calls, 0);
});

test('failed CSRF fetch prevents a write', async () => {
  const apply = interceptor(async () => ({ ok: false }));
  await assert.rejects(apply({ url: '/app/api/v1/auth/logout', method: 'POST' }), /CSRF/);
});

test('deployed root path fetches /api/v1/auth/csrf, without a duplicate /api', async () => {
  const calls = [];
  const apply = vm.runInNewContext('(' + source + ')', {
    URL, window: { location: { origin: 'https://stock-spoon.com',
      href: 'https://stock-spoon.com/api/swagger-ui/index.html',
      pathname: '/api/swagger-ui/index.html' } },
    fetch: async url => { calls.push(url); return { ok: true,
      json: async () => ({ token: 'masked-token', header_name: 'X-XSRF-TOKEN' }) }; }
  });
  const request = await apply({ url: '/api/v1/auth/login', method: 'POST', headers: {} });
  assert.deepEqual(calls, ['/api/v1/auth/csrf']);
  assert.equal(request.headers['X-XSRF-TOKEN'], 'masked-token');
});
