'use strict';

const { createHash } = require('node:crypto');
const { createReadStream, promises: fs } = require('node:fs');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');
const { listMainCaches } = require('./cache-metadata.cjs');

const prefix = 'migrations-buildkit-oci-v1-';
const hash = (text) => createHash('sha256').update(text).digest('hex');
const digestPattern = /^sha256:([0-9a-f]{64})$/;
const maxBytes = 5_000_000_000;
const member = (digest) => `.migrations-public-dependency-cache-v1/buildkit/${digest}`;
const media = [
  'application/octet-stream',
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.oci.image.index.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
  'application/vnd.docker.distribution.manifest.list.v2+json',
];
const tagPrefix = (name) => `${prefix}tag-${hash(name)}-`;
const objectPrefix = (digest) => `${prefix}${digest}-`;

async function fileHash(file) {
  const h = createHash('sha256');
  for await (const chunk of createReadStream(file)) h.update(chunk);
  return h.digest('hex');
}

// A ref is published after every reachable object. Its key contains the root descriptor.
// HEAD resolves only Actions metadata. GET downloads a single verified OCI object.
function reader({
  toolkit,
  directory,
  lookup = async (key) =>
    (
      await listMainCaches({
        repository: process.env.GITHUB_REPOSITORY,
        token: process.env.INPUT_CATALOG_TOKEN,
        prefix: key,
      })
    ).find((row) => row.key.startsWith(key))?.key,
}) {
  const descriptors = new Map(),
    files = new Map();
  return async (req, res) => {
    const match =
      /^\/v2\/buildkit-cache\/([a-z0-9._/-]+)\/(manifests|blobs)\/(cache(?:_amd64|_arm64)?|sha256:[0-9a-f]{64})(?:\?[^#]*)?$/.exec(
        req.url,
      );
    if (!match) return false;
    if (!['GET', 'HEAD'].includes(req.method)) {
      res.writeHead(405).end();
      return true;
    }
    const [, repo, kind, ref] = match;
    const requested = digestPattern.exec(ref)?.[1];
    const base = requested ? objectPrefix(requested) : tagPrefix(`${repo}:${ref}`);
    if (!descriptors.has(base))
      descriptors.set(
        base,
        lookup(base).catch((error) => {
          descriptors.delete(base);
          throw error;
        }),
      );
    const key = await descriptors.get(base);
    const suffix =
      key?.startsWith(base) && /^(?:([0-9a-f]{64})-)?(\d{1,10})-([0-4])$/.exec(key.slice(base.length));
    const digest = requested || suffix?.[1];
    if (!suffix || !digest || Number(suffix[2]) > maxBytes || (kind === 'manifests' && suffix[3] === '0')) {
      res.writeHead(404).end();
      return true;
    }
    const size = Number(suffix[2]);
    const headers = {
      'Docker-Distribution-Api-Version': 'registry/2.0',
      'Docker-Content-Digest': `sha256:${digest}`,
      'Content-Type': media[Number(suffix[3])],
      'Content-Length': size,
    };
    if (req.method === 'HEAD') {
      res.writeHead(200, headers).end();
      return true;
    }
    if (!files.has(digest))
      files.set(
        digest,
        (async () => {
          const file = path.join(directory, digest);
          const cacheKey = `${objectPrefix(digest)}${size}-${suffix[3]}`;
          const restored = await toolkit.restoreCache([member(digest)], cacheKey, [], {
            destination: file,
            maxBytes: size,
          });
          if (
            restored !== cacheKey ||
            (await fs.stat(file)).size !== size ||
            (await fileHash(file)) !== digest
          )
            throw new Error('Missing or corrupt BuildKit object');
          console.log(`Restored BuildKit OCI object sha256:${digest} (${size} bytes)`);
          return file;
        })().catch((error) => {
          files.delete(digest);
          throw error;
        }),
      );
    const file = await files.get(digest);
    res.writeHead(200, headers);
    await pipeline(createReadStream(file), res);
    return true;
  };
}

async function publish(root, toolkit) {
  const storage = path.join(root, 'docker/registry/v2');
  const blob = (digest) => path.join(storage, 'blobs/sha256', digest.slice(0, 2), digest, 'data');
  const seen = new Map();
  async function save(file, key, data) {
    if ((await toolkit.restoreCache([file], key, [], { lookupOnly: true })) === key) return;
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.copyFile(data, file);
    try {
      await toolkit.saveCache([file], key);
      if ((await toolkit.restoreCache([file], key, [], { lookupOnly: true })) !== key)
        throw new Error('BuildKit cache write unavailable');
    } finally {
      await fs.rm(file, { force: true });
    }
  }
  async function store(digest, type) {
    const hex = digestPattern.exec(digest)?.[1];
    if (!hex) throw new Error('Invalid BuildKit digest');
    if (seen.has(hex)) return seen.get(hex);
    const file = blob(hex),
      size = (await fs.stat(file)).size;
    if (size > maxBytes) throw new Error('BuildKit blob exceeds Actions cache size limit');
    let index = media.indexOf(type);
    if (type === 'root' || index > 0) {
      const manifest = JSON.parse(await fs.readFile(file, 'utf8'));
      index = media.indexOf(manifest.mediaType || type);
      if (index <= 0) throw new Error('Unsupported BuildKit manifest');
      for (const child of [
        ...(manifest.manifests || []),
        ...(manifest.layers || []),
        ...(manifest.config ? [manifest.config] : []),
      ])
        await store(child.digest, child.mediaType);
    }
    index = Math.max(0, index);
    const descriptor = `${size}-${index}`;
    await save(member(hex), objectPrefix(hex) + descriptor, file);
    seen.set(hex, descriptor);
    return descriptor;
  }
  async function walk(dir, relative = '') {
    for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      if (entry.name !== '_manifests') {
        if (!entry.name.startsWith('_'))
          await walk(path.join(dir, entry.name), relative ? `${relative}/${entry.name}` : entry.name);
        continue;
      }
      const tags = path.join(dir, entry.name, 'tags');
      for (const tag of await fs.readdir(tags)) {
        if (!/^cache(?:_amd64|_arm64)?$/.test(tag)) continue;
        const link = path.join(tags, tag, 'current/link');
        const digest = (await fs.readFile(link, 'utf8')).trim();
        const descriptor = await store(digest, 'root');
        const hex = digestPattern.exec(digest)[1];
        const base = tagPrefix(`${relative}:${tag}`);
        await save(`${member(hex)}.ref`, `${base}${hex}-${descriptor}`, link);
      }
    }
  }
  await walk(path.join(storage, 'repositories'));
  if (!seen.size) throw new Error('Build did not export any BuildKit cache manifests');
  console.log(`Published ${seen.size} content-addressed BuildKit objects`);
}
module.exports = { reader, publish, tagPrefix, objectPrefix };
