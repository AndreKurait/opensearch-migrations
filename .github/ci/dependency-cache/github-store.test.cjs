'use strict';

const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { createStore, selectRecent } = require('./github-store.cjs');

const digest = data => createHash('sha256').update(data).digest('hex');
const make = (key, data, accessed) => ({ key, digest: digest(data), size: Buffer.byteLength(data), type: 'application/octet-stream', accessed });
const missing = (key, accessed) => ({ key, digest: null, size: 0, type: '', accessed, missing: true });
const generation = '00000000000000000001';

async function fixture(t) {
  const before = process.cwd();
  const beforeWorkspace = process.env.GITHUB_WORKSPACE;
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'dep-cache-test-'));
  process.chdir(dir);
  process.env.GITHUB_WORKSPACE = dir;
  t.after(async () => {
    process.chdir(before);
    if (beforeWorkspace === undefined) delete process.env.GITHUB_WORKSPACE;
    else process.env.GITHUB_WORKSPACE = beforeWorkspace;
    await fs.rm(dir, { recursive: true, force: true });
  });
  const backend = new Map();
  const events = [];
  const toolkit = {
    async restoreCache([file], key, prefixes, { lookupOnly }) {
      events.push({ op: lookupOnly ? 'lookup' : 'restore', key });
      const matched = backend.has(key) ? key : [...backend.keys()].reverse().find(k => prefixes.some(prefix => k.startsWith(prefix)));
      if (!matched || backend.get(matched).file !== file) return undefined;
      if (!lookupOnly) await fs.writeFile(file, backend.get(matched).data);
      return matched;
    },
    async saveCache([file], key) {
      events.push({ op: 'save', key });
      if (backend.has(key)) return -1;
      backend.set(key, { file, data: await fs.readFile(file) });
      return backend.size;
    },
  };
  const direct = fn => fn();
  const store = createStore(toolkit, { read: direct, write: direct, delay: async () => {} });
  return { dir, backend, events, toolkit, store };
}

test('rolling LRU counts shared bytes once and retains known public negatives', () => {
  const newA = make('/maven/a', 'aaa', 7);
  const alias = make('/raw/alias', 'aaa', 2);
  const newerB = make('/raw/b', 'bbbb', 5);
  const oldC = make('/raw/c', 'cc', 1);
  const negative = missing('/maven/unknown', 4);
  const list = selectRecent([newA, alias, newerB, oldC, negative, make('/maven/a', 'previous', 0)], 6);
  assert.deepEqual(list.map(e => e.key), [newA.key, negative.key, alias.key, oldC.key]);
  assert.deepEqual(selectRecent([newA, missing(newA.key, 8)], 0), [missing(newA.key, 8)]);
  assert.throws(() => selectRecent([newA, { ...alias, size: 1 }]), /Conflicting sizes/);
});

test('a reader restores the index first, rejects unknowns, and fetches requested content only once', async t => {
  const { store, events } = await fixture(t);
  const files = new Map([['/raw/kind', Buffer.alloc(70_000, 'k')], ['/raw/helm', Buffer.alloc(70_000, 'h')]]);
  const one = make('/raw/kind', files.get('/raw/kind'), 2);
  const two = make('/raw/helm', files.get('/raw/helm'), 1);
  const alias = { ...one, key: '/raw/alias' };
  files.set(alias.key, files.get(one.key));
  const entries = [one, two, alias, missing('/maven/probe', 3)];
  assert.deepEqual(await store.publish({ entries, generation, materialize: (e, file) => fs.writeFile(file, files.get(e.key)) }),
    { generation, entries: 4, blobs: 2, bytes: 140_000 });
  assert.match(events.filter(e => e.op === 'save').at(-1).key, /index-/);
  events.length = 0;
  const reader = await store.openReader();
  assert.equal(events.length, 1);
  assert.equal(await reader.resolve('/maven/probe'), null);
  await assert.rejects(reader.resolve('/raw/unknown'), { code: 'CACHE_MISS' });
  const [kind, other] = await Promise.all([reader.resolve('/raw/kind'), reader.resolve('/raw/alias')]);
  assert.deepEqual(kind, other);
  assert.deepEqual(await fs.readFile(kind.path), files.get('/raw/kind'));
  assert.equal(events.filter(e => e.op === 'restore' && e.key.includes('blob-')).length, 1);
  await reader.close();
  await assert.rejects(fs.access(kind.path), { code: 'ENOENT' });
});

test('objects packed together retain individual hashes; a missing or corrupt object is never a public 404', async t => {
  const { store, events, backend } = await fixture(t);
  const candidates = new Map();
  let pair;
  for (let i = 0; !pair; i++) {
    const data = `small file ${i}`;
    const entry = make(`/maven/${i}`, data, i);
    const prefix = entry.digest.slice(0, 2);
    if (candidates.has(prefix)) pair = [candidates.get(prefix), { data, entry }];
    else candidates.set(prefix, { data, entry });
  }
  const content = new Map(pair.map(p => [p.entry.key, p.data]));
  const entries = pair.map(p => p.entry);
  await store.publish({ entries, generation, materialize: (e, file) => fs.writeFile(file, content.get(e.key)) });
  const cacheKey = [...backend.keys()].find(k => k.includes('blob-pack-'));
  assert.ok(cacheKey);
  const original = backend.get(cacheKey);
  let reader = await store.openReader();
  events.length = 0;
  for (const entry of entries) assert.equal(await fs.readFile((await reader.resolve(entry.key)).path, 'utf8'), content.get(entry.key));
  assert.equal(events.filter(e => e.op === 'restore').length, 1);
  await reader.close();
  reader = await store.openReader();
  backend.delete(cacheKey);
  await assert.rejects(reader.resolve(entries[0].key), { code: 'CACHE_MISS' });
  backend.set(cacheKey, { ...original, data: Buffer.alloc(original.data.length, 'a') });
  await assert.rejects(reader.resolve(entries[0].key), /SHA-256 mismatch/);
  backend.set(cacheKey, original);
  assert.equal((await reader.resolve(entries[0].key)).digest, entries[0].digest);
  await reader.close();
});

test('publication fails closed, can resume, and a retried generation cannot describe different bytes', async t => {
  const { store, backend, toolkit } = await fixture(t);
  const entry = make('/raw/cli', 'original bytes', 9);
  const correct = (e, file) => fs.writeFile(file, 'original bytes');
  await assert.rejects(store.publish({ entries: [entry], generation, materialize: (e, file) => fs.writeFile(file, 'tampered bytes') }), /SHA-256/);
  assert.equal(backend.size, 0);
  const save = toolkit.saveCache;
  toolkit.saveCache = async (paths, key) => key.includes('index-') ? -1 : save(paths, key);
  await assert.rejects(store.publish({ entries: [entry], generation, materialize: correct }), /publication failed/);
  assert.equal(backend.size, 1);
  await assert.rejects(store.openReader(), { code: 'CACHE_MISS' });
  toolkit.saveCache = save;
  await store.publish({ entries: [entry], generation, materialize: () => assert.fail('Materialized a previously published object') });
  await assert.rejects(store.publish({ entries: [], generation, materialize: correct }), /different index/);
  backend.delete([...backend.keys()].find(k => k.includes('blob-')));
  let materialized = false;
  await store.publish({ entries: [entry], generation, materialize: async (...args) => { materialized = true; await correct(...args); } });
  assert.equal(materialized, true);
});
