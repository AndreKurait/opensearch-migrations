'use strict';

const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const {openRecorder, openPublisher} = require('./aws-store.cjs');

function fixture() {
  const objects = new Map();
  const etag = data => `"${createHash('sha256').update(data).digest('hex')}"`;
  return {objects, storage: {
    async put(key, filename, digest, expectedEtag) {
      const bytes = await fs.readFile(filename);
      if (digest && objects.has(key)) return;
      if ((objects.has(key) ? etag(objects.get(key)) : undefined) !== expectedEtag) {
        throw Object.assign(new Error('Conditional put conflict'), {code: 'PreconditionFailed'});
      }
      objects.set(key, bytes);
    },
    async get(key, destination) {
      if (!objects.has(key)) throw new Error('Unknown stored fixture');
      await fs.writeFile(destination, objects.get(key));
      return {ETag: etag(objects.get(key))};
    },
    async *catalog() {
      yield* [...objects.keys()].filter(key => key.includes('/catalog/')).sort();
    },
  }};
}

function origin(body) {
  let hits = 0;
  return {
    get hits() { return hits; },
    validateKey(key) { return key; },
    async fetchPublic(key, destination) {
      hits++;
      if (body instanceof Error) throw body;
      if (body === null) return null;
      await fs.writeFile(destination, body);
      return {size: Buffer.byteLength(body), type: 'text/plain',
        digest: createHash('sha256').update(body).digest('hex')};
    },
  };
}

test('concurrent workers retain independent observed keys, coalesce GETs and choose newest mutable representation', async t => {
  const {storage, objects} = fixture();
  const olderOrigin = origin('old');
  const older = await openRecorder({storage, proxy: olderOrigin, now: () => 10});
  const newer = await openRecorder({storage, proxy: origin('new'), now: () => 20});
  const missing = await openRecorder({storage, proxy: origin(null), now: () => 30});
  await Promise.all([older.resolve('maven/tag'), older.resolve('maven/tag'), older.resolve('maven/a'),
    newer.resolve('maven/tag'), newer.resolve('maven/b'), missing.resolve('maven/b')]);
  assert.equal(olderOrigin.hits, 2);
  await Promise.all([newer.close(), missing.close()]);
  await older.close();  // Earlier observations flushed last cannot replace a newer tag or authoritative 404.
  assert.equal([...objects.keys()].filter(k => k.includes('/blobs/')).length, 2);
  const pub = await openPublisher({storage, proxy: olderOrigin});
  const byKey = new Map(pub.entries.map(e => [e.key, e]));
  assert.equal(byKey.get('maven/tag').accessed, 20);
  assert.deepEqual(byKey.get('maven/b'), {key: 'maven/b', missing: true, digest: null, size: 0, type: '', accessed: 30, verifiedAt: 30});
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'test-dependency-read-'));
  t.after(() => fs.rm(dir, {recursive: true, force: true}));
  const dest = path.join(dir, 'blob');
  await pub.materialize(byKey.get('maven/tag'), dest);
  assert.equal(await fs.readFile(dest, 'utf8'), 'new');
  await assert.rejects(pub.materialize(byKey.get('maven/b'), dest), /unpublished/);
  objects.set('dependency-cache/v1/blobs/' + byKey.get('maven/tag').digest, Buffer.from('bad'));
  await assert.rejects(pub.materialize(byKey.get('maven/tag'), dest), /verification failed/);
  await assert.rejects(fs.stat(dest), {code: 'ENOENT'});
  await pub.close();
});

test('transient upstream errors produce no negative receipt, later observations can restore a missing key', async () => {
  const {storage} = fixture();
  for (const [at, response] of [[1, null], [2, new Error('upstream unavailable')], [3, 'found']]) {
    const recorder = await openRecorder({storage, proxy: origin(response), now: () => at});
    if (response instanceof Error) await assert.rejects(recorder.resolve('maven/artifact'), /unavailable/);
    else await recorder.resolve('maven/artifact');
    await recorder.close();
    const {entries} = await openPublisher({storage, proxy: origin(null)});
    assert.equal(entries[0].accessed, at === 2 ? 1 : at);
    assert.equal(Boolean(entries[0].missing), at < 3);
  }
});

test('main observations preserve access times, reuse verified mappings and reject stale or false representations', async () => {
  const {storage} = fixture();
  let time = 100;
  const record = await openRecorder({storage, proxy: origin('public'), now: () => time});
  const {digest} = await record.resolve('maven/a');
  time = 120;
  await record.resolve('maven/a');
  assert.equal((await record.close())[0].accessed, 120);
  const fetch = origin(new Error('network must not be used'));
  const next = await openRecorder({storage, proxy: fetch, now: () => 260});
  await next.importObservation({key: 'maven/a', digest, accessed: 200});
  await next.importObservation({key: 'maven/a', digest, accessed: 150});
  assert.equal(fetch.hits, 0);
  assert.equal((await next.resolve('maven/a', {digest, accessed: 190})).digest, digest);
  await assert.rejects(next.importObservation({key: 'maven/b', digest, accessed: 220}), /network must not/);
  await assert.rejects(next.importObservation({key: 'maven/a', digest, accessed: 400000}), /Invalid/);
  assert.equal((await next.close())[0].accessed, 200);
  const stale = await openRecorder({storage, proxy: origin('new-version'), now: () => 280});
  await assert.rejects(stale.importObservation({key: 'maven/b', digest, accessed: 270}), /no longer matches/);
  await assert.rejects(stale.importObservation({key: 'maven/a', missing: true, accessed: 275}), /no longer matches/);
  assert.equal((await stale.close()).length, 0);
  assert.equal((await openPublisher({storage, proxy: fetch})).entries[0].accessed, 200);
});


test('later main runs reuse digest-addressed OCI blobs and refresh tag-addressed manifests', async () => {
  const {storage} = fixture();
  const data = 'public registry manifest';
  const digest = createHash('sha256').update(data).digest('hex');
  const pinned = '/v2/dockerhub/library/hello-world/manifests/sha256:' + digest;
  const tag = '/v2/dockerhub/library/hello-world/manifests/latest';
  const first = await openRecorder({storage, proxy: origin(data)});
  await Promise.all([first.resolve(pinned), first.resolve(tag)]);
  await first.close();
  const changed = origin('new public registry manifest');
  const second = await openRecorder({storage, proxy: changed});
  const cached = await second.resolve(pinned);
  assert.equal(await fs.readFile(cached.path, 'utf8'), data);
  assert.equal(changed.hits, 0);
  const current = await second.resolve(tag);
  assert.equal(await fs.readFile(current.path, 'utf8'), 'new public registry manifest');
  assert.equal(changed.hits, 1);
  await second.close();
});

test('native snapshots ignore newer routes without making them restorable or writable', async () => {
  const {storage} = fixture();
  const writer = await openRecorder({storage, proxy:origin('bytes')});
  for (const key of ['/future/item', '/supported/item']) await writer.importObservation({key, accessed:Date.now()});
  await writer.close();
  const proxy = {validateKey(key) { if (key.startsWith('/future/')) throw Error('unsupported'); return key; }};
  await assert.rejects(openPublisher({storage, proxy}), /unsupported/);
  await assert.rejects(openRecorder({storage, proxy}), /unsupported/);
  const snapshot = await openPublisher({storage, proxy, supportedRoutesOnly:true});
  assert.deepEqual(snapshot.entries.map(row => row.key), ['/supported/item']);
  await assert.rejects(snapshot.materialize({...snapshot.entries[0],key:'/future/item'}, '/unused'), /unpublished/);
  await snapshot.close();
});
