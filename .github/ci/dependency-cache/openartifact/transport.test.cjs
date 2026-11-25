'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { openPackageTransport, readModel, nativePath } = require('./transport.cjs');
const { validateRows } = require('./stack.cjs');
const { openColdStore, openReadStore } = require('./cold.cjs');

test('empty or evicted caches use bounded public fallback only for genuine misses', async () => {
  const miss = async () => { throw Object.assign(new Error('evicted'), {code: 'CACHE_MISS'}); };
  const key = '/maven/release.jar';
  const artifact = {digest: 'a'.repeat(64), size: 15};
  const cold = {resolve: async () => artifact};
  const options = {cold, reportMiss() {}, openReader: miss};
  const empty = await openReadStore(options);
  assert.deepEqual(empty.entries, []);
  assert.deepEqual(await empty.resolve(key), artifact);
  await assert.rejects(openReadStore({...options, cold: null}), {code: 'CACHE_MISS'});
  await assert.rejects(openReadStore({...options, openReader: async () => { throw new Error('bad index'); }}), /bad index/);
  const prior = {entries: [{key, ...artifact}], resolve: miss};
  const evicted = await openReadStore({...options, openReader: async () => prior});
  assert.deepEqual(await evicted.resolve(key), artifact);
  cold.resolve = async () => ({...artifact, digest: 'b'.repeat(64)});
  await assert.rejects(evicted.resolve(key), /no longer matches/);
});

test('derive OpenArtifact cache metadata directly from published main paths, including Gradle fallback misses', () => {
  const sample = {
    key: '/maven/org/example/a.jar',
    digest: 'a'.repeat(64),
    status: 200,
    type: 'application/java-archive',
    size: 123,
    accessed: Date.now(),
  };
  const missing = {
    ...sample,
    key: '/maven/org/example/missing.pom',
    digest: null,
    type: '',
    size: 0,
    missing: true,
  };
  const oci = { ...sample, key: '/v2/dockerhub/hello-world/manifests/latest' };
  const artifacts = readModel([sample, missing, oci, { ...sample, key: '/raw/gradle/dist.zip' }]);
  assert.deepEqual(
    artifacts.snapshot.map((row) => row.repo),
    ['maven-central', 'maven-central', 'maven-plugins', 'dockerhub'],
  );
  assert.equal(artifacts.snapshot.at(-1).key, 'library/hello-world/manifests/latest');
  assert.deepEqual(artifacts.blobs, [sample, oci]);
  const qualified = { ...oci, key: '/v2/dockerhub/library/hello-world/manifests/latest', accessed: oci.accessed + 5 };
  assert.deepEqual(readModel([oci, qualified]).snapshot, [
    { ...artifacts.snapshot.at(-1), accessed: qualified.accessed },
  ]);
  assert.equal(nativePath('/raw/k3s/releases/k3s'), null);
  assert.throws(() => readModel([{ ...sample, key: '/maven/a?token=unsafe' }]));
  assert.throws(() => validateRows([{ ...artifacts.snapshot[0], password: 'private' }]));
  assert.throws(() => validateRows([{ ...artifacts.snapshot[0], repo: 'unapproved' }]));
});

test('unpublished public dependencies coalesce, are bounded and disappear with the job', async (t) => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'cold-check-'));
  t.after(() => fs.rm(temp, { recursive: true, force: true }));
  let calls = 0;
  const cold = await openColdStore(temp, {
    maxEntries: 2,
    maxBytes: 5,
    maxObjectBytes: 3,
    fetchArtifact: async (key, file, { maxBytes }) => {
      calls++;
      if (key.endsWith('too-big')) throw new Error('oversize');
      assert.ok(maxBytes >= 2);
      await fs.writeFile(file, 'ok');
      return { size: 2, type: 'text/plain', digest: 'a'.repeat(64) };
    },
  });
  const key = '/maven/example/a.jar';
  const items = await Promise.all([cold.resolve(key), cold.resolve(key)]);
  assert.deepEqual(items[0], items[1]);
  assert.equal(calls, 1);
  await assert.rejects(cold.resolve('/maven/too-big'), /oversize/);
  await cold.resolve('/maven/example/b.jar');
  await assert.rejects(cold.resolve('/maven/example/c.jar'), /budget/);
  await cold.close();
  await assert.rejects(fs.stat(items[0].path), { code: 'ENOENT' });
});

test('non-native requests and unpublished strict misses never start Docker services', async () => {
  for (const mode of ['record', 'read']) {
    const transport = await openPackageTransport({mode, reader: {entries: []}});
    assert.equal(await transport.handle({}, {}, '/pypi/simple/build'), false);
    if (mode === 'read') await assert.rejects(transport.handle({}, {}, '/maven/missing.pom'), {code: 'CACHE_MISS'});
    assert.deepEqual(await transport.observations(), []);
    assert.equal(transport.metrics, undefined);
    await transport.close();
  }
});
