'use strict';

const {spawn} = require('node:child_process');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const {Readable, Transform} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const {pathToFileURL} = require('node:url');
const {onHostedMac, requestGitHub} = require('./darwin-cache-transport.cjs');

const INDEX_BYTES = 32 * 1024 * 1024;
const MAX_OBJECT_BYTES = 5_000_000_000;
const validMember = (file) =>
  typeof file === 'string' &&
  file.length <= 2048 &&
  file.split('/').every((part) => /^[a-zA-Z0-9_.-]+$/.test(part) && part !== '.' && part !== '..');

function finished(child) {
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (code, signal) =>
      code === 0 ? resolve() : reject(new Error(`Dependency cache archive rejected (${signal || code})`)),
    );
  });
}

// The only destination is chosen by the trusted caller. A member name in the archive never selects a file to create.
async function extractOne(input, member, destination, maxBytes) {
  if (
    !validMember(member) ||
    !path.isAbsolute(destination) ||
    !Number.isSafeInteger(maxBytes) ||
    maxBytes < 0 ||
    maxBytes > MAX_OBJECT_BYTES
  )
    throw new Error('Invalid cache extraction bounds');
  const privateDir = await fs.mkdtemp(path.join(path.dirname(destination), '.migrations-cache-restore-'));
  const staged = path.join(privateDir, randomUUID());
  // zstd bounds its window before allocating decompression memory. The validating reader bounds expanded content, headers and padding.
  const zstd = spawn('zstd', ['-d', '-q', '-c', '--long=30'], {
    stdio: ['pipe', 'pipe', 'ignore'],
    // Standard macOS hosted images ship zstd in Homebrew. Never inherit a candidate-controlled PATH.
    env: {PATH: process.platform === 'darwin' ? '/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin' : '/usr/bin:/bin'},
  });
  const parser = spawn(
    'python3',
    ['-I', path.join(__dirname, 'github-restore.py'), member, staged, String(maxBytes)],
    {stdio: ['pipe', 'ignore', 'ignore'], env: {PATH: '/usr/bin:/bin'}},
  );
  const ceiling = maxBytes + Math.ceil(maxBytes / 128) + 1024 * 1024;
  let received = 0;
  const bounded = new Transform({
    transform(chunk, _, cb) {
      received += chunk.length;
      cb(received > ceiling ? new Error('Compressed dependency cache entry exceeds its bound') : null, chunk);
    },
  });
  // Attach pipeline error handling in the same turn as adapting the HTTP response.
  const source = input instanceof Readable ? input : Readable.fromWeb(input);
  const tasks = [
    finished(zstd),
    finished(parser),
    pipeline(source, bounded, zstd.stdin),
    pipeline(zstd.stdout, parser.stdin),
  ];
  try {
    await Promise.all(tasks);
    await fs.link(staged, destination); // Atomic, same filesystem, no overwrite or symlink following at destination.
  } finally {
    source.destroy();
    zstd.kill();
    parser.kill();
    await Promise.allSettled(tasks);
    await fs.rm(privateDir, {recursive: true, force: true});
  }
}

let toolkitInternals;
async function internals() {
  // v6.3.0 exposes save/restore but no safe download-only API. Use its version calculation and authenticated v2 metadata client, pinned by package-lock.
  const lib = path.join(__dirname, 'node_modules', '@actions', 'cache');
  toolkitInternals ||= Promise.all(
    ['cacheUtils.js', 'shared/cacheTwirpClient.js', 'config.js', '../generated/results/api/v1/cache.twirp-client.js'].map(
      (file) => import(pathToFileURL(path.join(lib, 'lib/internal', file)).href),
    ),
  );
  return toolkitInternals;
}

async function cacheVersionCalculator() {
  const [utils] = await internals();
  const compression = await utils.getCompressionMethod();
  if (!['zstd', 'zstd-without-long'].includes(compression)) throw new Error('GitHub dependency caches require zstd');
  return paths => utils.getCacheVersion(paths, compression, false);
}

async function cacheDownload(paths, key, restoreKeys) {
  const [utils, twirp, config, generated] = await internals();
  if (!config.isCacheReadable(config.getCacheMode())) return {ok: false};
  if (config.getCacheServiceVersion() !== 'v2') throw new Error('GitHub dependency caches require cache service v2');
  const version = (await cacheVersionCalculator())(paths);
  const serviceURL = new URL(config.getCacheServiceURL());
  try {
    let client = twirp.internalCacheTwirpClient();
    if (onHostedMac()) {
      client = new generated.CacheServiceClientJSON({
        async request(service, method, contentType, data) {
          const endpoint = new URL(`/twirp/${service}/${method}`, serviceURL);
          const response = await requestGitHub(endpoint, 'metadata', {
            method: 'POST', headers: {'Content-Type': contentType, Authorization: `Bearer ${utils.getRuntimeToken()}`},
            body: JSON.stringify(data), signal: AbortSignal.timeout(45_000),
          });
          if (!response.ok) throw Object.assign(new Error('Cache metadata HTTP error'), {code:`HTTP_${response.status}`});
          return response.json();
        },
      });
    }
    return await client.GetCacheEntryDownloadURL({key, restoreKeys, version});
  } catch (error) {
    throw safeFailure(serviceURL.hostname, 'metadata', error);
  }
}

function safeFailure(host, phase, error) {
  const codes = [error?.cause?.code, error?.code, error?.name].filter(value => typeof value === 'string' && /^[a-z0-9_]{1,40}$/i.test(value));
  const reason = `Cache ${phase} at ${host} failed (${codes.join(',') || 'rejected'}).`;
  console.warn(reason);
  return new Error(reason);
}

const downloadArchive = (url, options) => onHostedMac() ? requestGitHub(url, 'archive', options) : fetch(url, options);

async function safeRestore(
  paths,
  primaryKey,
  restoreKeys = [],
  {lookupOnly = false, maxBytes = INDEX_BYTES, destination, download = cacheDownload, request = downloadArchive} = {},
) {
  if (paths.length !== 1 || !validMember(paths[0]))
    throw new Error('Expected a single relative cache member');
  const response = await download(paths, primaryKey, restoreKeys);
  if (!response.ok) return undefined;
  if (
    response.matchedKey !== primaryKey &&
    !restoreKeys.some((prefix) => response.matchedKey.startsWith(prefix))
  )
    return undefined;
  if (lookupOnly) return response.matchedKey;
  const url = new URL(response.signedDownloadUrl);
  if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Invalid cache archive URL');
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const data = await request(url, {redirect: 'error', signal: AbortSignal.timeout(15 * 60 * 1000)});
      if (!data.ok || !data.body) throw Object.assign(new Error('Cache archive HTTP error'), {code:`HTTP_${data.status}`});
      await extractOne(data.body, paths[0], destination || path.resolve(paths[0]), maxBytes);
      break;
    } catch (error) {
      const failure = safeFailure(url.hostname, 'archive', error);
      if (attempt === 3 || error.code === 'EEXIST' || /^HTTP_4(?!08|29)/.test(error.code)) throw failure;
      await new Promise(resolve => setTimeout(resolve, attempt * 250));
    }
  }
  return response.matchedKey;
}
module.exports = {safeRestore, extractOne, INDEX_BYTES, cacheVersionCalculator};
