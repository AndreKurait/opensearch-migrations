'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { promises: fs } = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { reader, publish } = require('./buildkit-cache.cjs');
const { bridgeRelay } = require('./client-routing.cjs');
const { startProxy } = require('./proxy.cjs');

test('registry reads the selected manifest and layer and never transfers a probed sibling', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'buildkit-lazy-test-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const registry = path.join(root, 'docker/registry/v2');
  const blobs = new Map(),
    reads = [];
  async function artifact(content, mediaType) {
    const digest = createHash('sha256').update(content).digest('hex');
    const file = path.join(registry, 'blobs/sha256', digest.slice(0, 2), digest, 'data');
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, content);
    return { digest: `sha256:${digest}`, size: Buffer.byteLength(content), mediaType };
  }
  const a = await artifact('needed', 'application/vnd.oci.image.layer.v1.tar+gzip');
  const b = await artifact('unrelated', 'application/vnd.oci.image.layer.v1.tar+gzip');
  const manifest = JSON.stringify({
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
    layers: [a, b],
  });
  const rootManifest = await artifact(manifest, 'application/vnd.oci.image.manifest.v1+json');
  const tag = path.join(registry, 'repositories/migrations/app/_manifests/tags/cache_amd64/current/link');
  await fs.mkdir(path.dirname(tag), { recursive: true });
  await fs.writeFile(tag, rootManifest.digest);
  const toolkit = {
    async saveCache([file], key) {
      blobs.set(key, await fs.readFile(file));
    },
    async restoreCache(_, key, __, options) {
      if (!blobs.has(key)) return;
      if (!options.lookupOnly) {
        reads.push(key);
        await fs.writeFile(options.destination, blobs.get(key));
      }
      return key;
    },
  };
  await publish(root, toolkit);
  const buildkitCache = reader({
    directory: root,
    toolkit,
    lookup: async (prefix) => [...blobs.keys()].find((key) => key.startsWith(prefix)),
  });
  const proxy = await startProxy({ resolve: () => assert.fail('origin accessed'), buildkitCache });
  t.after(proxy.close);
  const relay = await bridgeRelay(new URL(proxy.url), '127.0.0.1');
  t.after(relay.close);
  const url = `http://127.0.0.1:${relay.port}/v2/buildkit-cache/migrations/app`;
  assert.equal(
    (await fetch(`${url}/manifests/cache_amd64`, { method: 'HEAD' })).headers.get('docker-content-digest'),
    rootManifest.digest,
  );
  assert.equal((await fetch(`${url}/blobs/${b.digest}`, { method: 'HEAD' })).status, 200);
  assert.deepEqual(reads, []);
  assert.equal(await (await fetch(`${url}/manifests/cache_amd64`)).text(), manifest);
  assert.equal(await (await fetch(`${url}/blobs/${a.digest}`)).text(), 'needed');
  assert.equal(await (await fetch(`${url}/blobs/${a.digest}`)).text(), 'needed');
  assert.equal(reads.length, 2);
  assert.ok(reads.every((key) => !key.includes(b.digest.slice(7))));
});
