'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {mkdtemp, readFile, rm, stat, writeFile} = require('node:fs/promises');
const {createHash} = require('node:crypto');
const {tmpdir} = require('node:os');
const path = require('node:path');
const {openLocalRecorder, readReceipt, MAX_RECEIPT_BYTES, MAX_RECEIPT_ENTRIES} = require('./receipts.cjs');
const {startProxy} = require('./proxy.cjs');

test('an OCI HEAD followed by concurrent full and ranged GETs downloads the blob once', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'head-then-get-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  const bytes = Buffer.from('oci-content');
  const digest = createHash('sha256').update(bytes).digest('hex');
  const key = `/v2/dockerhub/example/image/blobs/sha256:${digest}`;
  let downloads = 0;
  const recorder = await openLocalRecorder({receiptFile: path.join(dir, 'receipt'), fetchArtifact: async (requested, file) => {
    assert.equal(requested, key);
    downloads++;
    await writeFile(file, bytes);
    return {type: 'application/octet-stream', size: bytes.length, digest};
  }});
  const proxy = await startProxy({resolve: recorder.resolve});
  try {
    const head = await fetch(proxy.url + key, {method: 'HEAD'});
    assert.equal(head.headers.get('docker-content-digest'), `sha256:${digest}`);
    assert.equal((await head.arrayBuffer()).byteLength, 0);
    const [full, range] = await Promise.all([
      fetch(proxy.url + key), fetch(proxy.url + key, {headers: {range: 'bytes=1-3'}}),
    ]);
    assert.deepEqual(Buffer.from(await full.arrayBuffer()), bytes);
    assert.deepEqual(Buffer.from(await range.arrayBuffer()), bytes.subarray(1, 4));
    assert.equal(downloads, 1);
  } finally { await proxy.close(); await recorder.close(); }
});

test('records the last real request time, coalesces, verifies observations, and sends no bytes or local path', async t => {
  const temp = await mkdtemp(path.join(tmpdir(), 'check-receipts-'));
  t.after(() => rm(temp, {recursive: true, force: true}));
  const receiptFile = path.join(temp, 'result', 'receipts.json');
  const clock = Date.now();
  let now = clock;
  const calls = [];
  const data = 'unpublished metadata';
  const digest = createHash('sha256').update(data).digest('hex');
  const good = '/maven/org/example/example.jar', missing = '/maven/org/example/missing.jar';
  const retry = '/raw/jdk/example.gz';
  const recorder = await openLocalRecorder({receiptFile, now: () => now, fetchArtifact: async (key, file) => {
    calls.push(key);
    now += 500;
    if (key === missing) return null;
    if (key === retry && calls.filter(k => k === retry).length === 1) throw new Error('temporary network failure');
    await writeFile(file, data);
    return {type: 'application/octet-stream', size: data.length, digest};
  }});
  const items = await Promise.all([recorder.resolve(good), recorder.resolve(good)]);
  assert.deepEqual(items[0], items[1]);
  assert.equal(await readFile(items[0].path, 'utf8'), data);
  assert.equal(await recorder.resolve(missing), null);
  await assert.rejects(recorder.resolve(retry), /temporary/);
  await recorder.resolve(retry);
  const lastAccess = now;
  assert.equal((await recorder.resolve(good)).path, items[0].path);
  assert.deepEqual(calls, [good, missing, retry, retry]);
  const entries = await recorder.close();
  assert.deepEqual(await recorder.close(), entries);
  assert.deepEqual(readReceipt(await readFile(receiptFile), now), entries);
  assert.deepEqual(entries.find(row => row.key === good), {key: good, accessed: lastAccess, digest});
  assert.deepEqual(entries.find(row => row.key === missing), {key: missing, accessed: clock + 500, missing: true});
  assert.equal(entries.find(row => row.key === retry).accessed, clock + 1500);
  await assert.rejects(stat(items[0].path), {code: 'ENOENT'});
  await assert.rejects(recorder.resolve(good), /closed/);
});

test('rejects extra fields, unsafe origins, duplicates, stale time, false digest or excessive input', () => {
  const now = Date.now();
  const initial = {key: '/maven/g/a.pom', accessed: now};
  assert.deepEqual(readReceipt(JSON.stringify([initial]), now), [initial]);
  for (const changes of [{size: 1024}, {path: '/private'}, {key: '/raw/127.0.0.1/x'}, {missing: false},
    {digest: 'sha256:broken'}, {accessed: 1}, {accessed: now + 600_000}, {missing: true, digest: 'a'.repeat(64)}]) {
    assert.throws(() => readReceipt(JSON.stringify([{...initial, ...changes}]), now));
  }
  assert.throws(() => readReceipt(JSON.stringify([initial, initial]), now), /Duplicate/);
  assert.throws(() => readReceipt(JSON.stringify(Array(MAX_RECEIPT_ENTRIES + 1).fill(null)), now), /count/);
  assert.throws(() => readReceipt(Buffer.alloc(MAX_RECEIPT_BYTES + 1), now), /size/);
  assert.throws(() => readReceipt('{"exports":["/raw/test/x"]}', now), /count/);
});
