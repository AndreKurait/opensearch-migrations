'use strict';

const {mkdtemp, mkdir, rename, rm, writeFile} = require('node:fs/promises');
const {randomUUID} = require('node:crypto');
const path = require('node:path');
const {tmpdir} = require('node:os');
const {fetchPublic, validateKey} = require('./proxy.cjs');

const MAX_RECEIPT_BYTES = 2 * 1024 * 1024;
const MAX_RECEIPT_ENTRIES = 8192;
const MAX_AGE = 30 * 24 * 60 * 60 * 1000;
const CLOCK_SKEW = 5 * 60 * 1000;

function readReceipt(text, now = Date.now()) {
  if ((typeof text !== 'string' && !Buffer.isBuffer(text)) || Buffer.byteLength(text) > MAX_RECEIPT_BYTES) {
    throw new Error('Invalid dependency receipt size');
  }
  const entries = JSON.parse(text.toString());
  if (!Array.isArray(entries) || entries.length > MAX_RECEIPT_ENTRIES) throw new Error('Invalid dependency receipt count');
  const seen = new Set();
  return entries.map(entry => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry) ||
        Object.keys(entry).some(field => !['key', 'accessed', 'digest', 'missing'].includes(field))) {
      throw new Error('Invalid dependency receipt fields');
    }
    const key = validateKey(entry.key);
    if (seen.has(key)) throw new Error('Duplicate dependency receipt key');
    seen.add(key);
    if (!Number.isSafeInteger(entry.accessed) || entry.accessed < now - MAX_AGE || entry.accessed > now + CLOCK_SKEW) {
      throw new Error('Invalid dependency access time');
    }
    if ('missing' in entry && (entry.missing !== true || 'digest' in entry)) throw new Error('Invalid missing dependency');
    if ('digest' in entry && (typeof entry.digest !== 'string' || !/^[a-f0-9]{64}$/.test(entry.digest))) {
      throw new Error('Invalid observed dependency digest');
    }
    return entry;
  });
}

function immutablePublicKey(key) {
  if (/^\/v2\/.+\/(?:manifests|blobs)\/sha256:[0-9a-f]{64}$/.test(key)) return true;
  if (key.startsWith('/helm/')) return true;
  if (key.startsWith('/maven/')) return !/(?:-SNAPSHOT\/|\/maven-metadata[^/]*\.xml)/i.test(key);
  if (/^\/npm\/.+\/-\/.+\.tgz$/.test(key) || key.startsWith('/pypi/packages/')) return true;
  return /^\/raw\/(?:gradle\/gradle-[\d.]+-(?:bin|all)\.zip|corretto\/resources\/[\d.]+\/|(?:k3s|kind|argo|crane)\/v[\d.]+|kubectl\/release\/v[\d.]+\/|helm\/helm-v[\d.]+-)/.test(key);
}

// Retention uses accessed. Freshness uses the last authoritative origin check.
function isFreshEntry(entry, now = Date.now()) {
  if (!entry) return false;
  if (!entry.missing && immutablePublicKey(entry.key)) return true;
  const ttl = entry.missing ? 60_000 : 300_000;
  return now - (entry.verifiedAt ?? entry.accessed) < ttl;
}

async function openLocalRecorder({receiptFile, fetchArtifact = fetchPublic, now = Date.now, lookup}) {
  if (typeof receiptFile !== 'string' || !receiptFile) throw new Error('A dependency receipt file is required');
  const dir = await mkdtemp(path.join(tmpdir(), 'dependency-recorder-'));
  const pending = new Map();
  const observed = new Map();
  let closed;

  async function record(entry) {
    const {key} = entry;
    const file = path.join(dir, randomUUID());
    if (lookup) {
      try {
        const published = await lookup(key);
        if (published === null) { entry.missing = true; return null; }
        if (published) { entry.digest = published.digest; return published; }
      } catch (error) {
        if (error.code !== 'CACHE_MISS') throw error;
      }
    }
    const item = await fetchArtifact(key, file);
    if (item === null) entry.missing = true;
    else entry.digest = item.digest;
    return item === null ? null : {...item, path: file};
  }
  return {
    async resolve(key) {
      if (closed) throw new Error('Dependency recorder is closed');
      validateKey(key);
      const accessed = now();
      if (pending.has(key)) {
        const entry = observed.get(key);
        entry.accessed = Math.max(entry.accessed, accessed);
        return pending.get(key);
      }
      if (pending.size >= MAX_RECEIPT_ENTRIES) throw new Error('Too many distinct dependency requests');
      const entry = {key, accessed};
      observed.set(key, entry);
      pending.set(key, record(entry).catch(error => {
        pending.delete(key);
        observed.delete(key);
        throw error;
      }));
      return pending.get(key);
    },
    close() {
      if (closed) return closed;
      closed = (async () => {
        const target = path.resolve(receiptFile);
        const temporary = `${target}.${randomUUID()}.tmp`;
        try {
          await Promise.allSettled(pending.values());
          const text = JSON.stringify([...observed.values()].sort((a, b) => a.key.localeCompare(b.key)));
          const entries = readReceipt(text, now());
          await mkdir(path.dirname(target), {recursive: true});
          await writeFile(temporary, text, {flag: 'wx', mode: 0o600});
          await rename(temporary, target);
          return entries;
        } finally {
          await rm(temporary, {force: true});
          await rm(dir, {recursive: true, force: true});
        }
      })();
      return closed;
    },
  };
}

module.exports = {openLocalRecorder, readReceipt, immutablePublicKey, isFreshEntry, MAX_RECEIPT_BYTES, MAX_RECEIPT_ENTRIES};
