'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {mkdtemp, readFile, rm, stat, writeFile} = require('node:fs/promises');
const {createHash} = require('node:crypto');
const {tmpdir} = require('node:os');
const path = require('node:path');
const {openLocalRecorder, readReceipt, isFreshEntry, reusableRecordingEntry, MAX_RECEIPT_BYTES, MAX_RECEIPT_ENTRIES} = require('./receipts.cjs');
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
    assert.deepEqual(recorder.metrics, {originLookups: 1, originPayloadBytes: bytes.length});
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

test('normal hosted recording uses published package metadata and known 404s without origin traffic', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'cache-first-metadata-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  const metadata = {digest: 'a'.repeat(64), path: '/published/file', size: 1};
  const recorder = await openLocalRecorder({receiptFile: path.join(dir, 'receipt'),
    lookup: async key => key === '/npm/test' ? metadata : null,
    fetchArtifact: () => assert.fail('unexpected public origin download')});
  assert.deepEqual(await recorder.resolve('/npm/test'), metadata);
  assert.equal(await recorder.resolve('/pypi/simple/no-package'), null);
  const observations = await recorder.close();
  assert.equal(observations.length, 2);
});

test('AL2023 snapshot reads stay warm while mirror lists and negative results expire', () => {
  const snapshot = `/raw/al2023/core/guids/${'a'.repeat(64)}/x86_64/repodata/repomd.xml`;
  const mirror = '/raw/al2023/core/mirrors/latest/x86_64/mirror.list';
  const verifiedAt = 0;
  assert.equal(isFreshEntry({key: snapshot, verifiedAt}, 600_000), true);
  assert.equal(isFreshEntry({key: mirror, verifiedAt}, 600_000), false);
  assert.equal(isFreshEntry({key: snapshot, verifiedAt, missing: true}, 600_000), false);
});

test('offline misses fail the request and leave a path-only receipt for protected publication', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'offline-receipt-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  const receiptFile = path.join(dir, 'requests.json');
  const key = '/maven/org/example/example.jar';
  const accessed = Date.now();
  const recorder = await openLocalRecorder({receiptFile, now: () => accessed, fetchArtifact: async () => {
    throw Object.assign(new Error('offline miss'), {code: 'UNPUBLISHED_DEPENDENCY'});
  }});
  await assert.rejects(recorder.resolve(key), {code: 'UNPUBLISHED_DEPENDENCY'});
  await assert.rejects(recorder.resolve(key), {code: 'UNPUBLISHED_DEPENDENCY'});
  await recorder.close();
  assert.deepEqual(readReceipt(await readFile(receiptFile)), [{key, accessed}]);
});

test('a recorded OCI HEAD observes the digest without restoring the layer until its first GET', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'record-metadata-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  const data = Buffer.from('layer');
  const digest = createHash('sha256').update(data).digest('hex');
  const key = `/v2/dockerhub/library/test/blobs/sha256:${digest}`;
  const item = {digest, size: data.length, type: 'application/octet-stream'};
  let restores = 0;
  const recorder = await openLocalRecorder({receiptFile: path.join(dir, 'receipt'), lookup: async () => {
    restores++;
    const file = path.join(dir, 'layer');
    await writeFile(file, data);
    return {...item, path: file};
  }});
  const proxy = await startProxy({catalog: new Map([[key, item]]), observeMetadata: recorder.observeMetadata,
    resolve: recorder.resolve});
  t.after(proxy.close);
  assert.equal((await fetch(proxy.url + key, {method: 'HEAD'})).status, 200);
  assert.equal(restores, 0);
  for (let i = 0; i < 2; i++) assert.deepEqual(Buffer.from(await (await fetch(proxy.url + key)).arrayBuffer()), data);
  assert.equal(restores, 1);
  const rows = await recorder.close();
  assert.deepEqual(rows.map(({key, digest}) => ({key, digest})), [{key, digest}]);
});


test('recording workers reuse published indexes until publisher refresh while misses expire normally', () => {
  const entry = {key: '/npm/example', verifiedAt: 0, accessed: 600_000};
  assert.equal(isFreshEntry(entry, 600_000), false);
  assert.equal(reusableRecordingEntry(entry, 600_000), true);
  assert.equal(reusableRecordingEntry({...entry, missing: true}, 600_000), false);
  assert.equal(reusableRecordingEntry(entry, 86_400_000), false);
});

test('recorders reuse a published negative release plugin marker while the publisher rechecks it', () => {
  const key = '/maven/example/example.gradle.plugin/1.0/example.gradle.plugin-1.0.jar';
  const entry = {key, missing: true, verifiedAt: 0};
  assert.equal(isFreshEntry(entry, 600_000), false);
  assert.equal(reusableRecordingEntry(entry, 600_000), true);
  assert.equal(reusableRecordingEntry(entry, 86_400_000), false);
  assert.equal(reusableRecordingEntry({...entry, key: key.replace('/1.0/', '/1.0-SNAPSHOT/')}, 600_000), false);
});

test('workers reuse release image tags for at most a day while the publisher refreshes them', () => {
  const prefix = '/v2/dockerhub/testcontainers/ryuk/manifests/';
  const entry = {key: prefix + '0.8.1', verifiedAt: 0};
  assert.equal(isFreshEntry(entry, 600_000), false);
  assert.equal(reusableRecordingEntry(entry, 600_000), true);
  assert.equal(reusableRecordingEntry(entry, 86_400_000), false);
  assert.equal(reusableRecordingEntry({...entry, key: prefix + 'latest'}, 600_000), false);
  assert.equal(reusableRecordingEntry({...entry, missing: true}, 600_000), false);
});

test('cached Maven artifact absences require an existing release POM for extended reuse', () => {
  const base = '/maven/org/example/demo/1.2.3/demo-1.2.3';
  const catalog = new Map([[base + '.pom', {digest: 'a'.repeat(64)}]]);
  for (const ext of ['.jar', '.module']) {
    const entry = {key: base + ext, verifiedAt: 0, missing: true};
    assert.equal(reusableRecordingEntry(entry, 600_000, catalog), true);
    assert.equal(reusableRecordingEntry(entry, 600_000, new Map()), false);
    assert.equal(reusableRecordingEntry(entry, 86_400_000, catalog), false);
  }
});

test('AL2023 RPMs remain fresh only when the content matches the blobstore digest', () => {
  const digest = 'a'.repeat(64);
  const key = `/raw/al2023/blobstore/${digest}/curl.x86_64.rpm`;
  const entry = {key, digest, verifiedAt: 0};
  assert.equal(isFreshEntry(entry, 86_400_000), true);
  assert.equal(isFreshEntry({...entry, digest: 'b'.repeat(64)}, 86_400_000), false);
  assert.equal(isFreshEntry({...entry, missing: true}, 86_400_000), false);
});
