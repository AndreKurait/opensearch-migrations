'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {mkdtemp, readdir, rm} = require('node:fs/promises');
const {tmpdir} = require('node:os');
const {join} = require('node:path');
const {archiveFor, fetchJdk} = require('./corretto.cjs');

test('maps native OS and architecture to four separately pinned Corretto JDKs', () => {
  const digests = new Set();
  for (const [platform, osName] of [['linux', 'linux'], ['darwin', 'macosx']]) {
    for (const [arch, name] of [['x64', 'x64'], ['arm64', 'aarch64']]) {
      const {filename, key, digest} = archiveFor(platform, arch);
      assert.equal(filename, `amazon-corretto-21.0.13.1.2-${osName}-${name}.tar.gz`);
      assert.equal(key, '/raw/corretto/resources/21.0.13.1.2/' + filename);
      assert.match(digest, /^[a-f0-9]{64}$/);
      digests.add(digest);
    }
  }
  assert.equal(digests.size, 4);
  assert.throws(() => archiveFor('win32', 'x64'), /Unsupported/);
});

test('a missing or tampered JDK fails without downloading from public upstream or producing a partial archive', async t => {
  const temp = await mkdtemp(join(tmpdir(), 'corretto-test-'));
  t.after(() => rm(temp, {recursive: true, force: true}));
  const expected = archiveFor('linux', 'x64').key;
  const keys = [];
  let status = 503;
  const server = http.createServer((req, res) => { keys.push(req.url); res.writeHead(status).end('invalid archive'); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeIdleConnections(); }));
  const proxy = `http://127.0.0.1:${server.address().port}`;
  await assert.rejects(fetchJdk('https://corretto.aws/', temp, 'linux', 'x64'), /loopback/);
  await assert.rejects(fetchJdk(proxy, temp, 'linux', 'x64'), {message: `Corretto download returned 503 for ${expected}`});
  status = 200;
  await assert.rejects(fetchJdk(proxy, temp, 'linux', 'x64'), /published SHA-256/);
  assert.deepEqual(keys, [expected, expected]);
  assert.deepEqual(await readdir(temp), []);
});
