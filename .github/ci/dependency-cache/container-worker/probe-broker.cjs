'use strict';

const fs = require('node:fs/promises');
const net = require('node:net');
const path = require('node:path');
const {fetchPublic, startProxy} = require('../proxy.cjs');
const {once} = require('node:events');

const repository = '/v2/dockerhub/library/busybox';
const digest = 'b7f3d86d6e84fc17718c48bcde1450807faa2d56704205c697b4bd5df7b9e29f';
async function run(dir) {
  const files = new Map();
  async function seed(section, ref) {
    if (!/^sha256:[a-f0-9]{64}$/.test(ref)) throw new Error('Invalid probe fixture digest');
    const key = `${repository}/${section}/${ref}`;
    const file = path.join(dir, ref.slice(7));
    const data = await fetchPublic(key, file);
    if (!data || data.digest !== ref.slice(7)) throw new Error('Missing pinned public probe artifact');
    files.set(key, {...data, path: file});
    return file;
  }
  const manifest = JSON.parse(await fs.readFile(await seed('manifests', 'sha256:' + digest), 'utf8'));
  await Promise.all([manifest.config, ...manifest.layers].map((ref) => seed('blobs', ref.digest)));
  const reads = new Set();
  let misses = 0;
  const proxy = await startProxy({
    resolve: async (key) => {
      if (!files.has(key)) {
        misses++;
        throw Object.assign(new Error('Fixture not in trusted catalog'), {code: 'CACHE_MISS'});
      }
      reads.add(key);
      return files.get(key);
    },
  });
  const port = Number(new URL(proxy.url).port);
  const unix = net.createServer((socket) => {
    const peer = net.connect(port, '127.0.0.1');
    peer.on('error', () => socket.destroy());
    socket.on('error', () => peer.destroy());
    peer.once('close', () => socket.destroy());
    socket.once('close', () => peer.destroy());
    socket.pipe(peer).pipe(socket);
  });
  unix.listen(path.join(dir, 'cache.sock'));
  await once(unix, 'listening');
  await fs.writeFile(path.join(dir, 'ready'), 'ready\n', {mode: 0o600});
  await new Promise((resolve) => process.once('SIGTERM', resolve));
  unix.close();
  await proxy.close();
  await fs.writeFile(
    path.join(dir, 'metrics.json'),
    JSON.stringify({seeded: files.size, read: reads.size, misses}),
  );
  if (files.size !== reads.size || !reads.size)
    throw new Error('Nested container did not consume the expected pinned manifest and layers');
}
run(process.argv[2]).catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
