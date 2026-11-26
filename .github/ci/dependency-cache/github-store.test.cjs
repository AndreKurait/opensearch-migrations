'use strict';

const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { createStore, selectRecent, discoverPublicIndex } = require('./github-store.cjs');

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
    async restoreCache([file], key, prefixes, { lookupOnly, destination }) {
      events.push({ op: lookupOnly ? 'lookup' : 'restore', key });
      const matched = backend.has(key) ? key : [...backend.keys()].reverse().find(k => prefixes.some(prefix => k.startsWith(prefix)));
      if (!matched || backend.get(matched).file !== file) return undefined;
      if (!lookupOnly) await fs.writeFile(destination || file, backend.get(matched).data);
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
  assert.deepEqual(reader.entries.map(e => e.key).sort(), entries.map(e => e.key).sort());
  assert.equal(await reader.resolve('/maven/probe'), null);
  await assert.rejects(reader.resolve('/raw/unknown'), { code: 'CACHE_MISS' });
  const [kind, other] = await Promise.all([reader.resolve('/raw/kind'), reader.resolve('/raw/alias')]);
  assert.deepEqual(kind, other);
  assert.deepEqual(await fs.readFile(kind.path), files.get('/raw/kind'));
  assert.equal(events.filter(e => e.op === 'restore' && e.key.includes('blob-')).length, 1);
  await reader.close();
  await assert.rejects(fs.access(kind.path), { code: 'ENOENT' });
});

test('small objects sharing a digest prefix restore individually; a missing or corrupt object is never a public 404', async t => {
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
  const cacheKey = [...backend.keys()].find(k => k.endsWith(`blob-object-${entries[0].digest}`));
  assert.ok(cacheKey);
  const original = backend.get(cacheKey);
  let reader = await store.openReader();
  events.length = 0;
  assert.equal(await fs.readFile((await reader.resolve(entries[0].key)).path, 'utf8'), content.get(entries[0].key));
  assert.deepEqual(events.filter(e => e.op === 'restore').map(e => e.key), [cacheKey]);
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


test('reader pins a default-branch generation and rejects a PR-shadowed index', async t => {
  const { store, backend } = await fixture(t);
  await store.publish({ entries: [], generation, materialize: async () => {} });
  const key = [...backend.keys()][0];
  const shadow = key.replace(/-[a-f0-9]{64}$/, '-' + 'a'.repeat(64));
  const selected = await discoverPublicIndex({ repository:'public/repo', branch:'refs/heads/main', request:async (url, options) => {
    assert.equal(url.hostname,'api.github.com');
    assert.equal(url.searchParams.get('ref'),'refs/heads/main');
    assert.equal(options.headers.Authorization,undefined);
    return {ok:true,json:async()=>({actions_caches:[{ref:'refs/pull/4/merge',key:shadow},{ref:'refs/heads/main',key}]})};
  }});
  assert.equal(selected,key);
  const real = backend.get(key);
  backend.set(key,{...real,data:Buffer.from(real.data.toString().replace('"entries":[]','"entries":[{}]'))});
  await assert.rejects(store.openReader({indexKey:selected}), /Invalid dependency cache index/);
  backend.delete(key);
  backend.set(shadow,real);
  await assert.rejects(store.openReader({indexKey:selected}), {code:'CACHE_MISS'});
});


test('Docker can request a published manifest by tag or digest from the same repository', async t => {
  const {store, events} = await fixture(t);
  const entry = make('/v2/dockerhub/library/registry/manifests/2', 'manifest', 2);
  await store.publish({entries:[entry], generation, materialize: (_, file) => fs.writeFile(file, 'manifest')});
  const reader = await store.openReader();
  t.after(reader.close);
  const alias = entry.key.replace(/2$/, `sha256:${entry.digest}`);
  assert.ok(reader.entries.some(row => row.key === alias));
  assert.deepEqual(await reader.resolve(alias), await reader.resolve(entry.key));
  assert.equal(events.filter(e => e.op === 'restore' && e.key.includes('blob-')).length, 1);
  await assert.rejects(reader.resolve(alias.replace('/registry/', '/other/')), {code:'CACHE_MISS'});
});
