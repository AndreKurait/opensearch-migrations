'use strict';

const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { startStack, validateRows } = require('./stack.cjs');
const { startAdapter } = require('./adapter.cjs');
const { validateKey } = require('../proxy.cjs');

function nativePath(key) {
  validateKey(key);
  if (key.startsWith('/maven/'))
    return {
      repo: 'maven-central',
      key: key.slice('/maven/'.length),
      path: '/repos/maven-all/' + key.slice('/maven/'.length),
    };
  if (!key.startsWith('/v2/')) return null;
  const [, , repo, ...parts] = key.split('/');
  if (repo === 'dockerhub' && parts.length === 3) parts.unshift('library');
  const asset = parts.join('/');
  return { repo, key: asset, path: `/v2/${repo}/${asset}` };
}

function readModel(entries) {
  const rows = new Map();
  const blobs = [];
  function add(row) {
    const identity = `${row.repo}/${row.key}`;
    const previous = rows.get(identity);
    if (!previous || previous.accessed < row.accessed) rows.set(identity, row);
  }
  for (const entry of entries) {
    const native = nativePath(entry.key);
    if (!native) continue;
    const row = {
      repo: native.repo,
      key: native.key,
      digest: entry.digest,
      type: entry.type,
      size: entry.size,
      status: entry.missing ? 404 : 200,
      accessed: entry.accessed,
    };
    add(row);
    if (!entry.missing) blobs.push(entry);
    if (native.repo === 'maven-central' && entry.missing) add({ ...row, repo: 'maven-plugins' });
  }
  return { snapshot: validateRows([...rows.values()]), blobs };
}

async function openPackageTransport({ mode, reader, imageMode = mode, coldAllowed = false, reportMiss = () => {} }) {
  let stack, adapter, starting;
  const observed = new Map();
  const published = new Map((reader?.entries || []).map((e) => [e.key, e]));
  async function start() {
    const imageIds = await require('./bootstrap.cjs').serviceImages(imageMode);
    if (reader?.entries) {
      const plan = readModel(reader.entries);
      adapter = await startAdapter({ entries: plan.blobs, resolve: (key) => reader.resolve(key) });
      try {
        stack = await startStack({ imageIds, mode, storage: { type: 's3', ...adapter }, snapshot: plan.snapshot });
      } catch (error) {
        await adapter.close();
        adapter = undefined;
        throw error;
      }
    } else if (mode === 'record') stack = await startStack({ imageIds, mode: 'record' });
    else throw new Error('An immutable published catalog is required for OpenArtifact playback');
  }
  return {
    async handle(req, res, key) {
      const target = nativePath(key);
      if (!target) return false;
      const lookup = published.get(key);
      if (mode === 'read' && (!lookup || (coldAllowed && !require('../receipts.cjs').isFreshEntry(lookup)))) {
        reportMiss(key);
        if (coldAllowed) return false; // The fixed-origin job-local resolver may fetch this one dependency.
        throw Object.assign(new Error('Missing published dependency'), { code: 'CACHE_MISS' });
      }
      starting ??= start();
      await starting;
      const headers = {};
      for (const h of ['accept', 'if-none-match', 'range', 'if-range'])
        if (req.headers[h]) headers[h] = req.headers[h];
      const response = await fetch(new URL(target.path, stack.url), {
        method: req.method,
        headers,
        redirect: 'error',
      });
      if (mode === 'read' && !response.ok && response.status >= 500) {
        await response.body?.cancel();
        if (coldAllowed) return false;
        reportMiss(key);
        throw Object.assign(new Error('Published storage object unavailable'), { code: 'CACHE_MISS' });
      }
      if (mode === 'record' && (response.ok || response.status === 304 || response.status === 404))
        observed.set(key, Date.now());
      const output = {};
      for (const h of [
        'content-type',
        'content-length',
        'content-range',
        'accept-ranges',
        'etag',
        'docker-content-digest',
        'docker-distribution-api-version',
        'cache-control',
        'vary',
      ]) {
        const value = response.headers.get(h);
        if (value !== null) output[h] = value;
      }
      res.writeHead(response.status, output);
      if (req.method === 'HEAD' || !response.body) res.end();
      else await pipeline(Readable.fromWeb(response.body), res);
      return true;
    },
    async observations() {
      if (mode !== 'record' || !observed.size) return [];
      const rows = await stack.snapshot();
      const byNative = new Map(rows.map((row) => [`${row.repo}/${row.key}`, row]));
      const output = [];
      for (const [key, accessed] of observed) {
        const target = nativePath(key);
        let row = byNative.get(`${target.repo}/${target.key}`);
        if (target.repo === 'maven-central' && (!row || row.status === 404))
          row = byNative.get(`maven-plugins/${target.key}`) || row;
        if (!row) throw new Error(`OpenArtifact has no corresponding public cache row for ${key}`);
        output.push(row.status === 404 ? { key, accessed, missing: true } : { key, accessed, digest: row.digest });
      }
      return output;
    },
    async close() {
      try {
        await stack?.close();
      } finally {
        await adapter?.close();
      }
    },
    get metrics() { return adapter?.metrics; },
  };
}

module.exports = { openPackageTransport, readModel, nativePath };
