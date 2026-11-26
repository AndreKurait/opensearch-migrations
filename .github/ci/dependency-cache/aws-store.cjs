'use strict';

const {createHash, randomUUID} = require('node:crypto');
const {createReadStream, createWriteStream} = require('node:fs');
const fs = require('node:fs/promises');
const {createRequire} = require('node:module');
const os = require('node:os');
const path = require('node:path');
const {isFreshEntry} = require('./receipts.cjs');
const {pipeline} = require('node:stream/promises');

const prefix = 'dependency-cache/v1';
const blobKey = digest => `${prefix}/blobs/${digest}`;
const catalogPrefix = `${prefix}/catalog/`;
const shardKey = key => catalogPrefix + (createHash('sha256').update(key).digest()[0] % 64)
  .toString(16).padStart(2, '0') + '.json';
const isDigest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

function awsStorage(bucket) {
  if (typeof bucket !== 'string' || !/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(bucket)) {
    throw new Error('CI_DEPENDENCY_BUCKET must identify the private dependency store');
  }
  // Installed only inside the AWS worker. PR jobs require only the GitHub cache client.
  const awsRequire = createRequire(path.join(__dirname, 'aws/package.json'));
  const {S3Client, PutObjectCommand, GetObjectCommand, paginateListObjectsV2} = awsRequire('@aws-sdk/client-s3');
  const s3 = new S3Client({region: 'us-west-2'});
  return {
    async put(key, filename, digest, etag) {
      const options = {Bucket: bucket, Key: key, ContentLength: (await fs.stat(filename)).size,
        ...(etag ? {IfMatch: etag} : {IfNoneMatch: '*'})};
      if (digest) options.ChecksumSHA256 = Buffer.from(digest, 'hex').toString('base64');
      for (let attempt = 0; ; attempt++) {
        const body = createReadStream(filename);
        try {
          await s3.send(new PutObjectCommand({...options, Body: body}));
          return;
        } catch (error) {
          if (error.name === 'PreconditionFailed' && digest) return;
          if (error.name !== 'ConditionalRequestConflict' || attempt === 3) throw error;
        } finally {
          body.destroy();
        }
      }
    },
    async get(key, destination) {
      const result = await s3.send(new GetObjectCommand({Bucket: bucket, Key: key}));
      await pipeline(result.Body, createWriteStream(destination, {mode: 0o600}));
      return {ETag: result.ETag};
    },
    async *catalog() {
      const pages = paginateListObjectsV2({client: s3}, {Bucket: bucket, Prefix: catalogPrefix});
      for await (const page of pages) {
        for (const row of page.Contents || []) yield row.Key;
      }
    },
    close: () => s3.destroy(),
  };
}

async function checkFile(filename, {digest, size}) {
  const hash = createHash('sha256');
  let received = 0;
  for await (const chunk of createReadStream(filename)) {
    hash.update(chunk);
    received += chunk.length;
  }
  if (received !== size || hash.digest('hex') !== digest) throw new Error('Dependency content verification failed');
}

function checkEntry(entry, validateKey) {
  const verifiedAt = entry?.verifiedAt ?? entry?.accessed;
  if (!entry || typeof entry.key !== 'string' || validateKey(entry.key) !== entry.key ||
      !Number.isSafeInteger(entry.accessed) || entry.accessed < 0 || !Number.isSafeInteger(verifiedAt) || verifiedAt < 0) {
    throw new Error('Invalid trusted dependency receipt');
  }
  if (entry.missing === true) {
    if (entry.digest !== null || entry.size !== 0 || entry.type !== '') throw new Error('Invalid missing receipt');
    return {key: entry.key, missing: true, digest: null, size: 0, type: '', accessed: entry.accessed, verifiedAt};
  }
  if (!isDigest(entry.digest) || !Number.isSafeInteger(entry.size) || entry.size < 0 ||
      typeof entry.type !== 'string' || !entry.type || entry.type.length > 256 || /[\r\n]/.test(entry.type)) {
    throw new Error('Invalid dependency representation');
  }
  return {key: entry.key, digest: entry.digest, size: entry.size, type: entry.type, accessed: entry.accessed, verifiedAt};
}

async function loadShard(storage, key, temp, validateKey) {
  const filename = path.join(temp, randomUUID());
  try {
    const {ETag} = await storage.get(key, filename);
    const data = JSON.parse(await fs.readFile(filename, 'utf8'));
    if (typeof ETag !== 'string' || !Array.isArray(data)) throw new Error('Invalid dependency catalog shard');
    const entries = new Map();
    for (const item of data) {
      const entry = checkEntry(item, validateKey);
      if (shardKey(entry.key) !== key || entries.has(entry.key)) throw new Error('Invalid catalog shard member');
      entries.set(entry.key, entry);
    }
    return {entries, etag: ETag};
  } finally {
    await fs.rm(filename, {force: true});
  }
}

function newer(a, b) {
  // A deterministic tie order ensures identical results regardless of which writer wins the S3 update.
  if (!b) return true;
  if (a.verifiedAt !== b.verifiedAt) return a.verifiedAt > b.verifiedAt;
  return a.accessed > b.accessed || (a.accessed === b.accessed && (a.digest || '') > (b.digest || ''));
}

function observedTime(value, now) {
  if (!Number.isSafeInteger(value) || value < 0 || value < now - 30 * 86400000 || value > now + 300000) {
    throw new Error('Invalid main-worker dependency observation time');
  }
  return Math.min(now, value);
}

async function mapLimited(items, task) {
  const queue = Array.from(items);
  let index = 0;
  await Promise.all(Array.from({length: Math.min(8, queue.length)}, async () => {
    while (index < queue.length) await task(queue[index++]);
  }));
}

async function readCatalog(storage, temp, validateKey) {
  const catalog = new Map();
  const keys = [];
  for await (const key of storage.catalog()) {
    if (!/^dependency-cache\/v1\/catalog\/[0-3][a-f0-9]\.json$/.test(key)) throw new Error('Invalid catalog object');
    keys.push(key);
  }
  await mapLimited(keys, async key => catalog.set(key, await loadShard(storage, key, temp, validateKey)));
  return catalog;
}

async function openRecorder(options = {}) {
  const proxy = options.proxy || require('./proxy.cjs');
  const storage = options.storage || awsStorage(process.env.CI_DEPENDENCY_BUCKET);
  const now = options.now || Date.now;
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'main-dependency-recorder-'));
  let catalog;
  try {
    catalog = await readCatalog(storage, temp, proxy.validateKey);
  } catch (error) {
    storage.close?.();
    await fs.rm(temp, {force: true, recursive: true});
    throw error;
  }
  const requests = new Map();
  const files = new Map();
  const entries = new Map();
  const pending = new Set();
  let closed = false;

  async function record(key, observation, wantFile) {
    if (closed) throw new Error('Dependency recorder is closed');
    proxy.validateKey(key);
    const current = now();
    const observedAt = observedTime(observation ? observation.accessed : current, current);
    if ((observation?.digest != null && !isDigest(observation.digest)) || (observation?.missing && observation.digest)) {
      throw new Error('Invalid main-worker dependency observation');
    }
    const known = catalog.get(shardKey(key))?.entries.get(key);
    let state;
    const pinned = key.startsWith('/v2/') ? /\/(?:blobs|manifests)\/sha256:([a-f0-9]{64})$/.exec(key)?.[1] : undefined;
    const expected = observation ? observation.digest : pinned;
    if (known && isDigest(expected) && known.digest === expected && isFreshEntry(known, current)) {
      // Trusted key/digest observations and digest-addressed OCI URLs can use the same verified mapping.
      state = {entry: known};
    } else {
      const identity = JSON.stringify([key, observation?.digest || null, Boolean(observation?.missing)]);
      if (!requests.has(identity)) {
        const request = (async () => {
          const filename = path.join(temp, randomUUID());
          const data = await proxy.fetchPublic(key, filename);
          if ((observation?.digest && data?.digest !== observation.digest) || (observation?.missing && data !== null)) {
            throw new Error('Public origin no longer matches the main-worker dependency observation');
          }
          const entry = checkEntry(data === null ? {key, missing: true, digest: null, size: 0, type: '', accessed: observedAt, verifiedAt: current} :
            {key, digest: data.digest, size: data.size, type: data.type, accessed: observedAt, verifiedAt: current}, proxy.validateKey);
          if (data !== null) {
            // fetchPublic hashes while streaming; S3 validates the same checksum before accepting the file.
            await storage.put(blobKey(entry.digest), filename, entry.digest);
          }
          return {entry, filename};
        })();
        requests.set(identity, request.catch(error => { requests.delete(identity); throw error; }));
      }
      state = await requests.get(identity);
    }
    const entry = {...state.entry, accessed: observedAt};
    if (newer(entry, entries.get(key))) entries.set(key, entry);
    if (!wantFile) return entry;
    if (entry.missing) return null;
    if (!state.filename) {
      if (!files.has(entry.digest)) {
        const filename = path.join(temp, randomUUID());
        files.set(entry.digest, storage.get(blobKey(entry.digest), filename)
          .then(() => checkFile(filename, entry)).then(() => filename));
      }
      state.filename = await files.get(entry.digest);
    }
    return {path: state.filename, digest: entry.digest, size: entry.size, type: entry.type};
  }

  function track(promise) {
    pending.add(promise);
    promise.then(() => pending.delete(promise), () => pending.delete(promise));
    return promise;
  }

  async function saveShard([key, updates]) {
    let shard = catalog.get(key) || {entries: new Map()};
    const filename = path.join(temp, randomUUID());
    for (let attempt = 0; attempt < 12; attempt++) {
      let changed = false;
      for (const entry of updates) {
        if (newer(entry, shard.entries.get(entry.key))) {
          shard.entries.set(entry.key, entry);
          changed = true;
        }
      }
      if (!changed) return;
      await fs.writeFile(filename, JSON.stringify(Array.from(shard.entries.values())));
      try {
        await storage.put(key, filename, null, shard.etag);
        return;
      } catch (error) {
        if (!['PreconditionFailed', 'ConditionalRequestConflict'].includes(error.code || error.name)) throw error;
        shard = await loadShard(storage, key, temp, proxy.validateKey);
      }
    }
    throw new Error('Concurrent dependency catalog updates exceeded the retry limit');
  }
  return {
    resolve: (key, observation) => track(record(key, observation, true)),
    importObservation: observation => track(record(observation.key, observation, false)),
    async close() {
      closed = true;
      try {
        await Promise.allSettled(pending);
        const byShard = new Map();
        for (const entry of entries.values()) {
          const key = shardKey(entry.key);
          if (!byShard.has(key)) byShard.set(key, []);
          byShard.get(key).push(entry);
        }
        await mapLimited(byShard, saveShard);
        return Array.from(entries.values());
      } finally {
        storage.close?.();
        await fs.rm(temp, {force: true, recursive: true});
      }
    },
  };
}

async function openPublisher(options = {}) {
  const proxy = options.proxy || require('./proxy.cjs');
  const storage = options.storage || awsStorage(process.env.CI_DEPENDENCY_BUCKET);
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'main-dependency-publisher-'));
  const entries = new Map();
  try {
    // An older native reader can serve its existing routes after the publisher adds new ones.
    // Validate all row metadata and shard bindings before exposing only locally supported keys.
    const catalog = await readCatalog(storage, temp, options.supportedRoutesOnly ? key => key : proxy.validateKey);
    for (const shard of catalog.values()) {
      for (const [key, value] of shard.entries) {
        if (options.supportedRoutesOnly) {
          try { proxy.validateKey(key); } catch { continue; }
        }
        entries.set(key, value);
      }
    }
  } catch (error) {
    storage.close?.();
    throw error;
  } finally {
    await fs.rm(temp, {force: true, recursive: true});
  }
  return {
    entries: Array.from(entries.values()).sort((a, b) => a.key.localeCompare(b.key)),
    async materialize(entry, destination) {
      const accepted = entries.get(entry.key);
      if (!accepted || accepted.missing || accepted.digest !== entry.digest) {
        throw new Error('Cannot materialize an unpublished dependency');
      }
      try {
        await storage.get(blobKey(accepted.digest), destination);
        await checkFile(destination, accepted);
      } catch (error) {
        await fs.rm(destination, {force: true});
        throw error;
      }
    },
    async close() { storage.close?.(); },
  };
}

module.exports = {openRecorder, openPublisher};
