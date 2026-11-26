'use strict';

const { createHash } = require('node:crypto');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { createReadStream, createWriteStream, promises: fs } = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');
const { setTimeout: sleep } = require('node:timers/promises');

const DEFAULT_BYTES = 30_000_000_000;
const PREFIX = 'migrations-public-deps-v1-';
const INDEX = `${PREFIX}index-`;
const STAGING = '.migrations-public-dependency-cache-v1';
const INDEX_PATH = `${STAGING}/index.json`;
const SMALL_BYTES = 64 * 1024;
const PACK_BYTES = 4 * 1024 * 1024;
const PACK_MEMBERS = 256;
// These shares cover a publisher and up to 48 simultaneous readers (the Gradle matrix alone has 30).
const READ_RATE = 1200;
const PUBLISH_LOOKUP_RATE = 240;
const sha = () => createHash('sha256');
const hash = value => sha().update(value).digest('hex');
const byName = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const dateOrder = (a, b) => b.accessed - a.accessed || byName(a.key, b.key);
const relativeBlobPath = id => `${STAGING}/blobs/${id}`;
const blobKey = id => `${PREFIX}blob-${id}`;
const missingCache = message => Object.assign(new Error(message), { code: 'CACHE_MISS' });

function prepareEntry(entry) {
  const { key, size, accessed, type } = entry;
  if (typeof key !== 'string' || !key || typeof type !== 'string' || !Number.isSafeInteger(size) || size < 0 ||
      !Number.isSafeInteger(accessed) || accessed < 0) throw new Error('Invalid dependency catalog entry');
  if (entry.missing === true) {
    if (entry.digest !== null || size !== 0 || type !== '') throw new Error(`Invalid missing dependency ${key}`);
    return { key, size, accessed, type, digest: null, missing: true };
  }
  const digest = typeof entry.digest === 'string' && entry.digest.replace(/^sha256:/, '');
  if (!/^[a-f0-9]{64}$/.test(digest)) throw new Error(`Invalid SHA-256 for ${key}`);
  return { key, digest, size, type, accessed };
}

function selectRecent(entries, maxBytes = DEFAULT_BYTES) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) throw new RangeError('Invalid maxBytes');
  const byKey = new Map();
  for (const raw of entries) {
    const entry = prepareEntry(raw);
    if (!byKey.has(entry.key) || entry.accessed >= byKey.get(entry.key).accessed) byKey.set(entry.key, entry);
  }
  const sorted = [...byKey.values()].sort(dateOrder);
  const chosen = new Set();
  const sizes = new Map();
  let used = 0;
  for (const entry of sorted) {
    if (entry.missing) continue;
    const previous = sizes.get(entry.digest);
    if (previous !== undefined && previous !== entry.size) throw new Error(`Conflicting sizes for ${entry.digest}`);
    sizes.set(entry.digest, entry.size);
    if (!chosen.has(entry.digest) && entry.size <= maxBytes - used) {
      chosen.add(entry.digest);
      used += entry.size;
    }
  }
  return sorted.filter(entry => entry.missing || chosen.has(entry.digest));
}

// Group small files by digest prefix, so normal catalog churn only affects that prefix's packs.
// The pack is concatenated bytes with a digest and length table authenticated by the index.
function layout(entries, individual = false) {
  const unique = new Map();
  for (const entry of entries) if (!entry.missing && !unique.has(entry.digest)) unique.set(entry.digest, entry);
  const blobs = [];
  const locations = new Map();
  let group = [];
  let bytes = 0;
  function finish() {
    if (group.length === 0) return;
    const id = group.length === 1 ? `object-${group[0].digest}` :
      `pack-${hash(group.map(e => `${e.digest} ${e.size}\n`).join(''))}`;
    let offset = 0;
    for (const entry of group) {
      locations.set(entry.digest, { id, offset });
      offset += entry.size;
    }
    blobs.push({ id, size: bytes, members: group });
    group = [];
    bytes = 0;
  }
  for (const entry of [...unique.values()].sort((a, b) => byName(a.digest, b.digest))) {
    if (individual) {
      locations.set(entry.digest, { id: `object-${entry.digest}`, offset: 0 });
      blobs.push({ id: `object-${entry.digest}`, size: entry.size, members: [entry] });
      continue;
    }
    const prev = group[0];
    if (prev && (prev.digest.slice(0, 2) !== entry.digest.slice(0, 2) || bytes + entry.size > PACK_BYTES ||
        group.length === PACK_MEMBERS || entry.size > SMALL_BYTES)) finish();
    group.push(entry);
    bytes += entry.size;
    if (entry.size > SMALL_BYTES) finish();
  }
  finish();
  return { blobs, locations };
}

function limiter(max) {
  let running = 0;
  const waiting = [];
  return async fn => {
    if (running >= max) await new Promise(resolve => waiting.push(resolve));
    else running++;
    try { return await fn(); } finally {
      const next = waiting.shift();
      if (next) next(); else running--;
    }
  };
}

function paced(perMinute, budgetedJobs) {
  let next = 0;
  const spacing = Math.ceil(60_000 * budgetedJobs / perMinute);
  return async fn => {
    const when = Math.max(next, Date.now());
    next = when + spacing;
    if (when > Date.now()) await sleep(when - Date.now());
    return fn();
  };
}

// GitHub limits are repository-wide. Allocate a share to each concurrent consumer,
// including the publisher. The workflow must bound the aggregate number of active jobs.
function budget(name, fallback) {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${name} must be a positive job count`);
  return value;
}
function toolkitStore(toolkit, options = {}) {
  const read = options.read || paced(READ_RATE, budget('DEPENDENCY_CACHE_READER_JOBS', 48));
  const publishRead = options.read || paced(PUBLISH_LOOKUP_RATE, budget('DEPENDENCY_CACHE_WRITER_JOBS', 1));
  const write = options.write || paced(180, budget('DEPENDENCY_CACHE_WRITER_JOBS', 1));
  const restore = (file, key, prefixes = [], lookupOnly = false, bounds = {}) =>
    read(() => toolkit.restoreCache([file], key, prefixes, { lookupOnly, ...bounds }));
  const publicationRestore = (file, key, prefixes = [], lookupOnly = false, bounds = {}) =>
    publishRead(() => toolkit.restoreCache([file], key, prefixes, { lookupOnly, ...bounds }));
  const exists = async (file, key) => (await publicationRestore(file, key, [], true)) === key;
  async function save(file, key) {
    let error;
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const id = await write(() => toolkit.saveCache([file], key));
        if (Number.isSafeInteger(id) && id >= 0) return;
      } catch (e) { error = e; }
      // A competing writer or a lost finalize response can leave a usable immutable entry.
      if (await exists(file, key)) return;
      if (attempt < 2) await (options.delay || sleep)(1000 * (attempt + 1));
    }
    throw new Error(`GitHub cache publication failed for ${key}`, { cause: error });
  }
  return { restore, publicationRestore, exists, save };
}

async function verified(file, entry) {
  const stat = await fs.lstat(file);
  if (!stat.isFile() || stat.size !== entry.size) throw new Error(`Size mismatch for ${entry.key}`);
  const digest = sha();
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  if (digest.digest('hex') !== entry.digest) throw new Error(`SHA-256 mismatch for ${entry.key}`);
}

async function makeBlob(blob, file, materialize, temp) {
  if (blob.members.length === 1) {
    await materialize(blob.members[0], file);
    await verified(file, blob.members[0]);
    return;
  }
  const destination = await fs.open(file, 'wx');
  try {
    for (const entry of blob.members) {
      const member = path.join(temp, entry.digest);
      try {
        await materialize(entry, member);
        await verified(member, entry);
        // Each member is explicitly bounded to SMALL_BYTES.
        await destination.writeFile(await fs.readFile(member));
      } finally { await fs.rm(member, { force: true }); }
    }
  } finally { await destination.close(); }
}

async function stageDirectory(publisher = false) {
  if (process.env.GITHUB_WORKSPACE && path.resolve(process.env.GITHUB_WORKSPACE) !== process.cwd()) {
    throw new Error('Run the dependency cache action with its working directory set to GITHUB_WORKSPACE');
  }
  if (publisher) await fs.mkdir(`${STAGING}/blobs`, { recursive: true, mode: 0o700 });
  return fs.mkdtemp(path.join(os.tmpdir(), 'migrations-public-deps-'));
}

const validIndexKey = key => typeof key === 'string' && key.startsWith(INDEX) && /^[0-9]{1,40}-[a-f0-9]{64}$/.test(key.slice(INDEX.length));

async function discoverPublicIndex({ repository, branch, token, request = fetch }) {
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) || typeof branch !== 'string' || !branch.startsWith('refs/heads/')) {
    throw new Error('Expected a base-branch ref and public GitHub repository for cache discovery');
  }
  const records = await require('./cache-metadata.cjs').listMainCaches({repository, prefix:INDEX, branch, token, request});
  const index = records.find(row => row.ref === branch && validIndexKey(row.key));
  if (!index) throw missingCache(`No digest-bound dependency index published on ${branch}; run the trusted cache publisher`);
  return index.key;
}

function createStore(toolkit, options = {}) {
  const store = toolkitStore(toolkit, options);
  async function publish({ entries, materialize, generation, maxBytes = DEFAULT_BYTES }) {
    if (typeof generation !== 'string' || !/^[0-9]{1,40}$/.test(generation)) throw new Error('Invalid cache generation');
    const chosen = selectRecent(entries, maxBytes);
    const { blobs } = layout(chosen, true);
    const index = JSON.stringify({ version: 2, generation, entries: chosen });
    const indexDigest = hash(index);
    const indexKey = `${INDEX}${generation}-${indexDigest}`;
    const temp = await stageDirectory(true);
    try {
      await fs.rm(INDEX_PATH, { force: true });
      // A published generation is immutable. A retry must agree on its contents.
      const previous = await store.publicationRestore(INDEX_PATH, indexKey, [`${INDEX}${generation}-`]);
      if (previous && (previous !== indexKey || await fs.readFile(INDEX_PATH, 'utf8') !== index)) {
        throw new Error(`Generation ${generation} already has a different index`);
      }
      let next = 0;
      let failure;
      async function worker() {
        while (!failure && next < blobs.length) {
          const blob = blobs[next++];
          const file = relativeBlobPath(blob.id);
          try {
            if (await store.exists(file, blobKey(blob.id))) continue;
            await fs.rm(file, { force: true });
            await makeBlob(blob, file, materialize, temp);
            await store.save(file, blobKey(blob.id));
          } catch (error) { failure ||= error; }
          finally { await fs.rm(file, { force: true }); }
        }
      }
      await Promise.all(Array.from({ length: Math.min(4, blobs.length) }, worker));
      if (failure) throw failure;
      // Readers discover this prefix only after every referenced content entry was published.
      await fs.writeFile(INDEX_PATH, index, { mode: 0o600 });
      if (!await store.exists(INDEX_PATH, indexKey)) await store.save(INDEX_PATH, indexKey);
      return { generation, entries: chosen.length, blobs: blobs.length, bytes: blobs.reduce((s, b) => s + b.size, 0) };
    } finally {
      await fs.rm(INDEX_PATH, { force: true });
      await fs.rm(temp, { recursive: true, force: true });
    }
  }

  async function openReader({ indexKey } = {}) {
    const temp = await stageDirectory();
    let index;
    try {
      const destination = path.join(temp, 'index.json');
      if (indexKey && !validIndexKey(indexKey)) throw new Error('Invalid base branch dependency index key');
      const key = await store.restore(INDEX_PATH, indexKey || `${INDEX}latest`, indexKey ? [] : [INDEX], false, {destination});
      if (!validIndexKey(key) || (indexKey && key !== indexKey)) throw missingCache('No verified dependency index accessible to this pull request; run the trusted cache publisher');
      const data = await fs.readFile(destination, 'utf8');
      index = JSON.parse(data);
      if (![1, 2].includes(index.version) || key !== `${INDEX}${index.generation}-${hash(data)}` || !Array.isArray(index.entries)) {
        throw new Error(`Invalid dependency cache index ${key}`);
      }
      index.entries = index.entries.map(prepareEntry);
    } catch (error) {
      await fs.rm(temp, { recursive: true, force: true });
      throw error;
    }
    const entries = manifestLookup(index.entries);
    const { blobs, locations } = layout(index.entries, index.version === 2);
    const blobMap = new Map(blobs.map(blob => [blob.id, blob]));
    const metrics = { archiveTransfers: 0, archivePayloadBytes: 0, catalogObjects: locations.size, restoredObjects: 0 };
    const pending = new Map();
    const limit = limiter(4);
    let closed = false;
    async function download(blob) {
      const member = relativeBlobPath(blob.id);
      const file = path.join(temp, blob.id);
      try {
        const key = blobKey(blob.id);
        await fs.rm(file, { force: true });
        if (await store.restore(member, key, [], false, {destination:file, maxBytes:blob.size}) !== key) throw missingCache(`Dependency cache entry ${blob.id} was evicted or inaccessible; re-run the trusted cache publisher`);
        const stat = await fs.lstat(file);
        if (!stat.isFile() || stat.size !== blob.size) throw new Error(`Invalid dependency cache blob length for ${blob.id}`);
        metrics.archiveTransfers++;
        metrics.archivePayloadBytes += stat.size;
        metrics.restoredObjects += blob.members.length;
        if (process.env.GITHUB_ACTIONS === 'true') console.log(`Dependency cache transfer ${JSON.stringify({archive: blob.id, bytes: stat.size, digests: blob.members.map(entry => entry.digest)})}`);
        for (const entry of blob.members) {
          const out = path.join(temp, entry.digest);
          if (blob.members.length === 1) {
            await verified(file, entry);
            // A rename avoids retaining a second copy of a large uncached image layer.
            try { await fs.rename(file, out); } catch (error) {
              if (error.code !== 'EXDEV') throw error;
              await fs.copyFile(file, out);
            }
          } else {
            const { offset } = locations.get(entry.digest);
            if (entry.size) await pipeline(createReadStream(file, { start: offset, end: offset + entry.size - 1 }), createWriteStream(out));
            else await fs.writeFile(out, '');
            await verified(out, entry);
          }
        }
      } catch (error) {
        await Promise.all(blob.members.map(entry => fs.rm(path.join(temp, entry.digest), { force: true })));
        throw error;
      } finally { await fs.rm(file, { force: true }); }
    }
    return {
      generation: index.generation,
      metrics,
      entries: [...entries].map(([key, entry]) => ({...entry, key})),
      async resolve(key) {
        if (closed) throw new Error('Dependency cache reader is closed');
        const entry = entries.get(key);
        if (!entry) throw missingCache(`Dependency ${key} is outside the published working set; record it with the trusted warmer`);
        if (entry.missing) return null;
        const { id } = locations.get(entry.digest);
        if (!pending.has(id)) {
          const operation = limit(() => download(blobMap.get(id))).catch(error => { pending.delete(id); throw error; });
          pending.set(id, operation);
        }
        await pending.get(id);
        return { path: path.join(temp, entry.digest), digest: entry.digest, size: entry.size, type: entry.type };
      },
      async close() {
        closed = true;
        await Promise.allSettled(pending.values());
        await fs.rm(temp, { recursive: true, force: true });
      },
    };
  }
  return { publish, openReader };
}

let toolkitPromise;
// Docker resolves a mutable tag and then requests those same bytes by digest.
// Keep the original published row so each storage backend verifies its own key.
function manifestLookup(rows) {
  const entries = new Map(rows.map(row => [row.key, row]));
  for (const row of rows) {
    if (row.missing || !row.key.startsWith('/v2/')) continue;
    const tag = /^(\/v2\/.+\/manifests\/)[\w][\w.-]{0,127}$/.exec(row.key);
    if (!tag) continue;
    const alias = `${tag[1]}sha256:${row.digest}`;
    if (!entries.has(alias)) entries.set(alias, row);
  }
  return entries;
}

async function loadToolkit() {
  // The toolkit includes the compressor in its cache version. Force both Linux runners to
  // use zstd so a publisher falling back to gzip cannot apparently succeed with an unreadable index.
  toolkitPromise ||= (async () => {
    try { await promisify(execFile)('zstd', ['--quiet', '--version']); }
    catch (error) { throw new Error('Install zstd on the dependency cache publisher and reader', { cause: error }); }
    const toolkit = await import('@actions/cache');
    const {safeRestore} = require('./github-restore.cjs');
    return {saveCache: toolkit.saveCache, restoreCache: safeRestore};
  })();
  return toolkitPromise;
}
let defaultStore;
async function storeForRunner() {
  defaultStore ||= loadToolkit().then(createStore);
  return defaultStore;
}
module.exports = {
  createStore, selectRecent, loadToolkit, discoverPublicIndex, manifestLookup,
  publish: async options => (await storeForRunner()).publish(options),
  openReader: async ({ indexKey } = {}) => {
    if (!indexKey && process.env.GITHUB_ACTIONS === 'true') {
      indexKey = await discoverPublicIndex({ repository:process.env.GITHUB_REPOSITORY,
        branch:`refs/heads/${process.env.CI_DEPENDENCY_BASE_BRANCH || 'main'}`, token:process.env.INPUT_CATALOG_TOKEN });
    }
    return (await storeForRunner()).openReader({indexKey});
  },
};
