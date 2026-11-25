'use strict';

const {createHash, randomUUID} = require('node:crypto');
const {createReadStream, createWriteStream} = require('node:fs');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {Transform, Readable} = require('node:stream');
const {pipeline} = require('node:stream/promises');

const digestPattern = /^[a-f0-9]{64}$/;
const uuid = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}';
const temporaryKey = new RegExp(`^(staging/${uuid}|uploads/${uuid}(?:\\.[0-9]{1,15})?)$`);
const fault = (status, code) => Object.assign(new Error(code), {status, code});
function blobKey(digest) {
  if (!digestPattern.test(digest)) throw fault(400, 'InvalidDigest');
  return `blobs/${digest.slice(0, 2)}/${digest.slice(2, 4)}/${digest}`;
}
function keyDigest(key) {
  if (temporaryKey.test(key)) return null;
  const digest = key.split('/').at(-1);
  if (!digestPattern.test(digest) || key !== blobKey(digest)) throw fault(400, 'InvalidObjectName');
  return digest;
}

async function openStorage({
  entries = [],
  resolve,
  materialize,
  maxBytes = 32_000_000_000,
  maxObjectBytes = 5_000_000_000,
  maxObjects = 50000,
  maxUploads = 32,
  maxParts = 10000,
  tempDirectory = os.tmpdir(),
} = {}) {
  if (
    ![maxBytes, maxObjectBytes, maxObjects, maxUploads, maxParts].every(
      (value) => Number.isSafeInteger(value) && value > 0,
    )
  ) {
    throw fault(400, 'InvalidStorageLimits');
  }
  const base = new Map();
  for (const entry of entries) {
    if (entry?.missing) continue;
    if (
      !entry ||
      typeof entry.key !== 'string' ||
      !digestPattern.test(entry.digest) ||
      !Number.isSafeInteger(entry.size) ||
      entry.size < 0 ||
      entry.size > maxObjectBytes
    )
      throw fault(400, 'InvalidCatalog');
    const key = blobKey(entry.digest);
    if (base.has(key) && base.get(key).size !== entry.size) throw fault(400, 'InvalidCatalog');
    base.set(key, {
      entry,
      size: entry.size,
      etag: `"${entry.digest}"`,
      modified: new Date(0),
      meta: {sha256: entry.digest},
    });
  }
  if (base.size > maxObjects) throw fault(400, 'InvalidCatalog');
  if (base.size && typeof resolve !== 'function' && typeof materialize !== 'function')
    throw fault(400, 'MissingResolver');
  const dir = await fs.mkdtemp(path.join(tempDirectory, 'oa-job-storage-'));
  const local = new Map(),
    deleted = new Set(),
    uploads = new Map(),
    pending = new Map();
  const metrics = {
    heads: 0,
    gets: 0,
    queuedReads: 0,
    localHits: 0,
    restores: 0,
    verifiedBytes: 0,
    bytesServed: 0,
    localBytes: 0,
    peakLocalBytes: 0,
    writes: 0,
    multipartStarted: 0,
    multipartCompleted: 0,
    multipartAborted: 0,
  };
  let used = 0;
  let partCount = 0;
  function reserve(n) {
    if (n < 0 || used + n > maxBytes) throw fault(507, 'InsufficientStorage');
    used += n;
    metrics.localBytes = used;
    metrics.peakLocalBytes = Math.max(metrics.peakLocalBytes, used);
  }
  function release(n) {
    used -= n;
    metrics.localBytes = used;
  }
  async function discard(file) {
    if (file?.owned) {
      await fs.rm(file.path, {force: true});
      release(file.size);
    }
  }
  async function receive(stream, digest) {
    const filename = path.join(dir, randomUUID());
    const sha = createHash('sha256'),
      md5 = createHash('md5');
    let size = 0;
    try {
      await pipeline(
        stream,
        new Transform({
          transform(chunk, encoding, done) {
            try {
              if (size + chunk.length > maxObjectBytes) throw fault(413, 'EntityTooLarge');
              reserve(chunk.length);
              size += chunk.length;
              sha.update(chunk);
              md5.update(chunk);
              done(null, chunk);
            } catch (error) {
              done(error);
            }
          },
        }),
        createWriteStream(filename, {flags: 'wx', mode: 0o600}),
      );
      const hex = sha.digest('hex');
      if (digest && hex !== digest) throw fault(400, 'BadDigest');
      return {
        path: filename,
        owned: true,
        size,
        digest: hex,
        etag: `"${md5.digest('hex')}"`,
        modified: new Date(),
      };
    } catch (error) {
      await fs.rm(filename, {force: true});
      release(size);
      throw error;
    }
  }
  function stat(key) {
    keyDigest(key);
    return local.get(key) || (!deleted.has(key) && base.get(key)) || null;
  }
  async function get(key) {
    const row = stat(key);
    if (!row) throw fault(404, 'NoSuchKey');
    if (row.path) {
      metrics.localHits++;
      return row;
    }
    if (pending.has(key)) {
      metrics.queuedReads++;
      return pending.get(key);
    }
    const loading = (async () => {
      let source;
      const ownSource = !resolve;
      reserve(row.size);
      try {
        if (resolve) {
          const file = await resolve(row.entry.key);
          if (file?.digest !== row.entry.digest || file.size !== row.size || typeof file.path !== 'string')
            throw fault(502, 'BackingObjectMismatch');
          source = file.path;
        } else {
          source = path.join(dir, randomUUID());
          await materialize(row.entry, source);
        }
        const s = await fs.lstat(source);
        if (!s.isFile() || s.size !== row.size) throw fault(502, 'BackingObjectMismatch');
        // Resolver files are job-private and already verified by github-store. Verify again before exposing any range.
        const sha = createHash('sha256');
        for await (const part of createReadStream(source)) sha.update(part);
        if (sha.digest('hex') !== row.entry.digest) throw fault(502, 'BackingObjectMismatch');
        row.path = source;
        row.owned = ownSource;
        metrics.restores++;
        metrics.verifiedBytes += row.size;
        return row;
      } catch (error) {
        release(row.size);
        if (ownSource && source) await fs.rm(source, {force: true});
        throw error;
      }
    })();
    pending.set(key, loading);
    try {
      return await loading;
    } finally {
      pending.delete(key);
    }
  }
  async function commit(key, file, meta = {}) {
    if (!local.has(key) && local.size >= maxObjects) {
      await discard(file);
      throw fault(507, 'TooManyObjects');
    }
    const old = local.get(key);
    file.meta = meta;
    local.set(key, file);
    deleted.delete(key);
    metrics.writes++;
    await discard(old);
    return file;
  }
  async function put(key, stream, meta, bodyHash) {
    const digest = keyDigest(key);
    if (digest && bodyHash && bodyHash !== digest) throw fault(400, 'BadDigest');
    return commit(key, await receive(stream, digest || bodyHash), meta);
  }
  function upload(key, id) {
    const u = uploads.get(id);
    if (!u || u.key !== key) throw fault(404, 'NoSuchUpload');
    return u;
  }
  function begin(key, meta) {
    keyDigest(key);
    if (uploads.size >= maxUploads) throw fault(429, 'TooManyUploads');
    const id = randomUUID();
    uploads.set(id, {key, id, meta, parts: new Map(), started: new Date()});
    metrics.multipartStarted++;
    return id;
  }
  async function part(key, id, number, stream, bodyHash) {
    const u = upload(key, id);
    if (!Number.isInteger(number) || number < 1 || number > 10000) throw fault(400, 'InvalidPart');
    const file = await receive(stream, bodyHash);
    if (uploads.get(id) !== u || u.completing) {
      await discard(file);
      throw fault(409, 'OperationAborted');
    }
    const old = u.parts.get(number);
    if (!old && partCount >= maxParts) {
      await discard(file);
      throw fault(507, 'TooManyParts');
    }
    if (!old) partCount++;
    u.parts.set(number, file);
    await discard(old);
    return file;
  }
  async function abort(key, id) {
    const u = upload(key, id);
    if (u.completing) throw fault(409, 'OperationAborted');
    uploads.delete(id);
    partCount -= u.parts.size;
    await Promise.all([...u.parts.values()].map(discard));
    metrics.multipartAborted++;
  }
  async function complete(key, id, requested) {
    const u = upload(key, id);
    if (u.completing) throw fault(409, 'OperationAborted');
    if (!requested.length || requested.length > 10000) throw fault(400, 'InvalidPart');
    let last = 0;
    const parts = requested.map(({number, etag}) => {
      const p = u.parts.get(number);
      if (number <= last || !p || p.etag !== etag) throw fault(400, 'InvalidPart');
      last = number;
      return p;
    });
    u.completing = true;
    try {
      async function* bytes() {
        for (const p of parts) yield* createReadStream(p.path);
      }
      const result = await put(key, Readable.from(bytes()), u.meta);
      uploads.delete(id);
      partCount -= u.parts.size;
      await Promise.all([...u.parts.values()].map(discard));
      metrics.multipartCompleted++;
      return result;
    } finally {
      u.completing = false;
    }
  }
  return {
    metrics,
    stat,
    get,
    put,
    begin,
    part,
    abort,
    complete,
    upload,
    uploads,
    list: () => [...new Set([...base.keys(), ...local.keys()])].filter((k) => !deleted.has(k)).sort(),
    async remove(key) {
      keyDigest(key);
      const row = local.get(key);
      local.delete(key);
      if (base.has(key)) deleted.add(key);
      await discard(row);
    },
    async close() {
      await Promise.allSettled(pending.values());
      await fs.rm(dir, {recursive: true, force: true});
    },
  };
}
module.exports = {openStorage, blobKey, keyDigest, fault};
