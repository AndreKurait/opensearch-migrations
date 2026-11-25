'use strict';
const fs = require('node:fs/promises');
const net = require('node:net');
const path = require('node:path');
const {openLocalRecorder} = require('./receipts.cjs');
const {openReader} = require('./github-store.cjs');
const {serve} = require('./cli.cjs');

async function service(mode, dir) {
  let stop;
  const finished = new Promise(resolve => { stop = resolve; });
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
  const store = mode === 'record' ? await openLocalRecorder({receiptFile: path.join(dir, 'receipt.json')}) : await openReader();
  const misses = new Set();
  const resolve = store.resolve.bind(store);
  store.resolve = async key => {
    try { return await resolve(key); }
    catch (error) {
      if (error.code === 'CACHE_MISS' && misses.size < 256) misses.add(key);
      throw error;
    }
  };
  await serve(store, mode, async env => {
    const upstream = new URL(env.CI_DEPENDENCY_PROXY_URL);
    const socket = path.join(dir, 'proxy.sock');
    const relay = net.createServer(local => {
      const remote = net.createConnection(Number(upstream.port), upstream.hostname);
      local.on('error', () => remote.destroy()); remote.on('error', () => local.destroy());
      local.once('close', () => remote.destroy()); remote.once('close', () => local.destroy());
      local.pipe(remote).pipe(local);
    });
    await new Promise((ok, fail) => { relay.once('error', fail); relay.listen(socket, ok); });
    try {
      await fs.chmod(socket, 0o600);
      await fs.writeFile(path.join(dir, 'ready.tmp'), JSON.stringify({...env, CI_DEPENDENCY_PROXY_SOCKET: socket}));
      await fs.rename(path.join(dir, 'ready.tmp'), path.join(dir, 'ready.json'));
      await finished;
    } finally { await new Promise(resolve => relay.close(resolve)); }
  });
  if (misses.size) await fs.writeFile(path.join(dir, 'misses.json'), JSON.stringify([...misses]));
  await fs.writeFile(path.join(dir, 'done'), '');
}
const [mode, dir] = process.argv.slice(2);
service(mode, dir).catch(async error => { await fs.writeFile(path.join(dir, 'failed'), error.message); process.exitCode = 1; });
