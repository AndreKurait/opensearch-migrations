'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const {mkdtemp, readFile, writeFile, rm, stat} = require('node:fs/promises');
const {tmpdir} = require('node:os');
const path = require('node:path');
const http = require('node:http');
const {startProxy, fetchPublic, validateKey} = require('./proxy.cjs');

const manifestType = 'application/vnd.oci.image.manifest.v1+json';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function scratch(t) {
  const dir = await mkdtemp(path.join(tmpdir(), 'artifact-proxy-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  return dir;
}
function mockFetch(t, responses) {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({url: new URL(url), headers: options.headers});
    assert.ok(responses.length, 'unexpected upstream call');
    return responses.shift();
  });
  return calls;
}
function get(base, key, method = 'GET', headers = {}) {
  const url = new URL(base);
  return new Promise((accept, reject) => {
    http.request({hostname: url.hostname, port: url.port, path: key, method, headers}, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => accept({status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks)}));
      res.on('error', reject);
    }).on('error', reject).end();
  });
}

test('native OCI, HEAD, conditional GET, ranges, and distinct offline miss', async t => {
  const dir = await scratch(t);
  const bytes = Buffer.from('{"schemaVersion":2}');
  const file = path.join(dir, 'bytes');
  const digest = hash(bytes);
  await writeFile(file, bytes);
  const keys = [];
  const proxy = await startProxy({resolve: async key => {
    keys.push(key);
    if (key.endsWith('/missing')) return null;
    if (key.endsWith('/offline')) throw Object.assign(new Error(), {code: 'CACHE_MISS'});
    return {path: file, type: manifestType, size: bytes.length, digest};
  }});
  t.after(proxy.close);
  const key = '/v2/dockerhub/library/test/manifests/v1';
  assert.equal((await get(proxy.url, '/v2/')).headers['docker-distribution-api-version'], 'registry/2.0');
  const head = await get(proxy.url, key, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.body.length, 0);
  assert.equal(head.headers['docker-content-digest'], `sha256:${digest}`);
  assert.equal(head.headers['content-length'], String(bytes.length));
  assert.equal((await get(proxy.url, key)).headers['content-type'], manifestType);
  assert.equal((await get(proxy.url, key, 'GET', {'if-none-match': head.headers.etag})).status, 304);
  const partial = await get(proxy.url, key, 'GET', {range: 'bytes=1-4'});
  assert.equal(partial.status, 206);
  assert.deepEqual(partial.body, bytes.subarray(1, 5));
  assert.equal(partial.headers['content-range'], `bytes 1-4/${bytes.length}`);
  assert.deepEqual((await get(proxy.url, key, 'GET', {range: 'bytes=-2'})).body, bytes.subarray(-2));
  assert.equal((await get(proxy.url, key, 'GET', {range: 'bytes=99-100'})).status, 416);
  assert.equal((await get(proxy.url, key + '/invalid')).status, 400);
  assert.equal((await get(proxy.url, key.replace('v1', 'missing'))).status, 404);
  const offline = await get(proxy.url, key.replace('v1', 'offline'));
  assert.equal(offline.status, 503);
  assert.equal(JSON.parse(offline.body).errors[0].message, `Missing published dependency ${key.replace('v1', 'offline')}`);
  assert.equal((await get(proxy.url, key.replace('v1', `sha256:${'a'.repeat(64)}`))).status, 502);
  const immutable = `/v2/dockerhub/library/test/blobs/sha256:${digest}`;
  assert.deepEqual((await get(proxy.url, immutable)).body, bytes);
  assert.deepEqual((await get(proxy.url, immutable, 'GET', {range: 'bytes=-3'})).body, bytes.subarray(-3));
  await writeFile(file, Buffer.alloc(bytes.length));
  assert.equal((await get(proxy.url, immutable)).status, 502);
  assert.equal((await get(proxy.url, key, 'PUT')).status, 405);
  const before = keys.length;
  for (const invalid of ['/raw/jdk/../x', '/maven/%2e%2e/x', '/maven/a%2fb', '/maven/a?token=1',
    '/raw/constructor/http', '/maven//tmp', '/maven/\\abc', '/v2/ghcr/example/blobs/sha256:invalid']) {
    assert.throws(() => validateKey(invalid));
    assert.equal((await get(proxy.url, invalid)).status, 400);
  }
  assert.equal(keys.length, before);
});

test('Maven falls back on a true Central 404 and caches exact uncompressed bytes', async t => {
  const dir = await scratch(t);
  const file = path.join(dir, 'artifact');
  const calls = mockFetch(t, [new Response('', {status: 404}), new Response('plugin', {headers: {'content-length': '6'}}),
    new Response('', {status: 404}), new Response('', {status: 404}), new Response('', {status: 429})]);
  assert.deepEqual(await fetchPublic('/maven/org/example/plugin.jar', file),
    {type: 'text/plain', size: 6, digest: hash('plugin')});
  assert.equal((await readFile(file)).toString(), 'plugin');
  assert.deepEqual(calls.map(c => c.url.hostname), ['repo.maven.apache.org', 'plugins.gradle.org']);
  assert.equal(await fetchPublic('/maven/org/example/not-found.pom', path.join(dir, 'missing')), null);
  await assert.rejects(fetchPublic('/maven/org/example/error.pom', path.join(dir, 'error')), /429/);
  assert.equal(calls.length, 5);
});

test('OCI authenticates anonymously only to registry realm and drops the token on CDN redirects', async t => {
  const dir = await scratch(t);
  const payload = 'blob';
  const digest = hash(payload);
  const challenge = 'Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/hello-world:pull"';
  const calls = mockFetch(t, [new Response(null, {status: 401, headers: {'www-authenticate': challenge}}),
    Response.json({token: 'public-scope'}), new Response(null, {status: 307, headers: {
      location: 'https://production.cloudflare.docker.com/blob?signature=sample'}}),
    new Response(payload, {headers: {'docker-content-digest': `sha256:${digest}`}})]);
  const result = await fetchPublic(`/v2/dockerhub/hello-world/blobs/sha256:${digest}`, path.join(dir, 'data'));
  assert.equal(result.digest, digest);
  assert.equal(calls[0].url.pathname, `/v2/library/hello-world/blobs/sha256:${digest}`);
  assert.equal(calls[1].url.searchParams.get('scope'), 'repository:library/hello-world:pull');
  assert.equal(calls[2].headers.authorization, 'Bearer public-scope');
  assert.equal(calls[3].headers.authorization, undefined);
});

test('rejects token realms, redirects, inconsistent sizes, bad media types and corrupt digests', async t => {
  const dir = await scratch(t);
  const file = path.join(dir, 'data');
  const digest = hash('original');
  const redirects = ['https://169.254.169.254/x', 'https://quay.io.evil.test/x', 'http://get.helm.sh/x',
    'https://user:password@get.helm.sh/x', 'https://get.helm.sh:8888/x'];
  const calls = mockFetch(t, [
    ...redirects.map(location => new Response(null, {status: 307, headers: {location}})),
    new Response(null, {status: 401, headers: {'www-authenticate': 'Bearer realm="https://example.test/token",service="ghcr.io"'}}),
    new Response('bad manifest', {headers: {'content-type': 'text/html'}}), new Response('altered'),
    new Response('size', {headers: {'content-length': '100'}}), new Response('unchanged'),
  ]);
  for (const _ of redirects) await assert.rejects(fetchPublic('/raw/helm/archive.tgz', file), /redirect/);
  await assert.rejects(fetchPublic('/v2/ghcr/org/repo/manifests/v1', file), /realm/);
  await assert.rejects(fetchPublic('/v2/ghcr/org/repo/manifests/v1', file), /headers/);
  await assert.rejects(fetchPublic(`/v2/ghcr/org/repo/blobs/sha256:${digest}`, file), /mismatch/);
  await assert.rejects(fetchPublic('/raw/helm/archive.tgz', file), /mismatch/);
  await assert.rejects(stat(file), {code: 'ENOENT'});
  await writeFile(file, 'keep');
  await assert.rejects(fetchPublic('/raw/jdk/archive.tgz', file), {code: 'EEXIST'});
  assert.equal(await readFile(file, 'utf8'), 'keep');
  assert.equal(calls.length, redirects.length + 5);
});

test('npm and Python metadata route package bytes locally and reject off-origin file URLs', async t => {
  const dir = await scratch(t);
  const tarball = 'https://registry.npmjs.org/@org/item/-/item-1.0.0.tgz';
  const wheel = 'https://files.pythonhosted.org/packages/ab/cd/idna-3.10-py3-none-any.whl';
  const responses = [Response.json({versions: {'1.0.0': {dist: {tarball}}}}),
    new Response('tgz'), Response.json({files: [{url: wheel, hashes: {sha256: hash('wheel')}}]},
      {headers: {'content-type': 'application/vnd.pypi.simple.v1+json'}}),
    new Response('wheel'), Response.json({versions: {'2': {dist: {tarball: 'https://unlisted.test/x'}}}})];
  const upstream = mockFetch(t, responses);
  const entries = new Map();
  const resolve = async key => {
    const file = path.join(dir, hash(key));
    if (!entries.has(key)) entries.set(key, {...await fetchPublic(key, file), path: file});
    return entries.get(key);
  };
  const proxy = await startProxy({resolve});
  t.after(proxy.close);
  const result = await get(proxy.url, '/npm/@org%2Fitem');
  const index = JSON.parse(result.body);
  assert.equal(index.versions['1.0.0'].dist.tarball, proxy.url + '/npm/@org/item/-/item-1.0.0.tgz');
  assert.equal((await get(proxy.url, new URL(index.versions['1.0.0'].dist.tarball).pathname)).body.toString(), 'tgz');
  const py = JSON.parse((await get(proxy.url, '/pypi/simple/idna/')).body);
  assert.equal(py.files[0].url, '/pypi/packages/ab/cd/idna-3.10-py3-none-any.whl');
  assert.equal((await get(proxy.url, py.files[0].url)).body.toString(), 'wheel');
  assert.equal((await get(proxy.url, '/npm/unknown')).status, 502);
  assert.equal(upstream.length, 5);
  assert.ok(entries.has('/npm/@org/item'));
  assert.ok(entries.has('/pypi/simple/idna'));
  const replay = await startProxy({resolve: async key => entries.get(key)});
  t.after(replay.close);
  assert.equal(JSON.parse((await get(replay.url, '/npm/@org/item')).body).versions['1.0.0'].dist.tarball,
    replay.url + '/npm/@org/item/-/item-1.0.0.tgz');
  for (const [authority, expected] of [['127.0.0.1:42801', 'http://127.0.0.1:42801'],
    ['127.0.0.1:99999', replay.url], ['unrelated.invalid', replay.url]]) {
    const response = await get(replay.url, '/npm/@org/item', 'GET', {host: authority});
    assert.equal(JSON.parse(response.body).versions['1.0.0'].dist.tarball, expected + '/npm/@org/item/-/item-1.0.0.tgz');
  }
});
