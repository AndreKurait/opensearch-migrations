'use strict';
const fs = require('node:fs/promises');
const net = require('node:net');
const path = require('node:path');
const { openLocalRecorder, readReceipt } = require('./receipts.cjs');
const { openReader } = require('./github-store.cjs');
const { serve } = require('./cli.cjs');
const { openPackageTransport } = require('./openartifact/transport.cjs');
const { openColdStore, openReadStore } = require('./openartifact/cold.cjs');

async function service(mode, dir) {
  let stop;
  const finished = new Promise((resolve) => {
    stop = resolve;
  });
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
  const receiptFile = path.join(dir, 'receipt.json');
  let seed;
  const misses = new Set();
  const reportMiss = (key) => {
    if (misses.size < 256) misses.add(key);
  };
  const hosted = process.env.GITHUB_ACTIONS === 'true' && process.env.RUNNER_ENVIRONMENT === 'github-hosted';
  const coldAllowed = mode === 'read' && hosted && process.env.CI_DEPENDENCY_STRICT_READ !== '1';
  const cold = coldAllowed ? await openColdStore(dir) : null;
  let packages, store;
  try {
    store = mode === 'record'
      ? await openLocalRecorder({receiptFile, lookup: (key) => seed?.resolve(key)})
      : await openReadStore({openReader, cold, reportMiss});
    if (hosted && mode === 'record') {
      try {
        seed = await openReader();
      } catch (error) {
        console.warn(
          `Main is recording without a previous published dependency index (${error.code || 'index unavailable'}).`,
        );
      }
    }
    if (hosted && process.platform === 'linux') {
      packages = await openPackageTransport({
        mode,
        reader: mode === 'read' ? store : seed,
        coldAllowed,
        reportMiss,
      });
    }
    await serve(
      store,
      mode,
      async (env) => {
        const upstream = new URL(env.CI_DEPENDENCY_PROXY_URL);
        const socket = path.join(dir, 'proxy.sock');
        const relay = net.createServer((local) => {
          const remote = net.createConnection(Number(upstream.port), upstream.hostname);
          local.on('error', () => remote.destroy());
          remote.on('error', () => local.destroy());
          local.once('close', () => remote.destroy());
          remote.once('close', () => local.destroy());
          local.pipe(remote).pipe(local);
        });
        await new Promise((ok, fail) => {
          relay.once('error', fail);
          relay.listen(socket, ok);
        });
        try {
          await fs.chmod(socket, 0o600);
          await fs.writeFile(
            path.join(dir, 'ready.tmp'),
            JSON.stringify({ ...env, CI_DEPENDENCY_PROXY_SOCKET: socket }),
          );
          await fs.rename(path.join(dir, 'ready.tmp'), path.join(dir, 'ready.json'));
          await finished;
        } finally {
          await new Promise((resolve) => relay.close(resolve));
        }
      },
      packages?.handle,
    );
    if (packages && mode === 'record') {
      const merged = new Map();
      for (const row of [
        ...readReceipt(await fs.readFile(receiptFile)),
        ...(await packages.observations()),
      ]) {
        if (!merged.has(row.key) || merged.get(row.key).accessed <= row.accessed) merged.set(row.key, row);
      }
      const text = JSON.stringify([...merged.values()]);
      readReceipt(text);
      await fs.writeFile(receiptFile, text, { mode: 0o600 });
    }
    if (packages?.metrics)
      await fs.writeFile(path.join(dir, 'openartifact-metrics.json'), JSON.stringify(packages.metrics));
    if (mode === 'read')
      await fs.writeFile(path.join(dir, 'transfer-metrics.json'), JSON.stringify({
        ...(store.metrics || { archiveTransfers: 0, archivePayloadBytes: 0 }),
        ...(cold?.metrics || { originLookups: 0, originPayloadBytes: 0 }),
      }));
  } finally {
    try {
      await packages?.close();
    } finally {
      try {
        await seed?.close();
      } finally {
        await cold?.close();
        await store?.close();
      }
    }
  }
  if (misses.size) await fs.writeFile(path.join(dir, 'misses.json'), JSON.stringify([...misses]));
  await fs.writeFile(path.join(dir, 'done'), '');
}
const [mode, dir] = process.argv.slice(2);
service(mode, dir).catch(async (error) => {
  await fs.writeFile(path.join(dir, 'failed'), error.message);
  process.exitCode = 1;
});
