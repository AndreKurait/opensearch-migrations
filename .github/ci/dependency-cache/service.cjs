'use strict';
const fs = require('node:fs/promises');
const net = require('node:net');
const path = require('node:path');
const { openLocalRecorder } = require('./receipts.cjs');
const { openReader } = require('./github-store.cjs');
const { serve } = require('./cli.cjs');
const { openPackageTransport } = require('./openartifact/transport.cjs');
const { openColdStore, openReadStore } = require('./openartifact/cold.cjs');

async function service(mode, dir) {
  process.env.CI_DEPENDENCY_ORIGIN_REPORT = path.join(dir, 'origin-requests.jsonl');
  await fs.writeFile(process.env.CI_DEPENDENCY_ORIGIN_REPORT, '', {flag: 'wx', mode: 0o600});
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
        const snapshot = await openReader();
        const {isFreshEntry} = require('./receipts.cjs');
        const entries = snapshot.entries.filter(entry => isFreshEntry(entry));
        const byKey = new Map(entries.map(entry => [entry.key, entry]));
        seed = {...snapshot, entries, resolve: key => isFreshEntry(byKey.get(key)) ? snapshot.resolve(key) : undefined};
        store.metadataCatalog = {get(key) {
          const entry = byKey.get(key);
          return isFreshEntry(entry) ? entry : undefined;
        }};
      } catch (error) {
        console.warn(
          `Main is recording without a previous published dependency index (${error.code || 'index unavailable'}).`,
        );
      }
    }
    if (hosted && process.platform === 'linux') {
      packages = await openPackageTransport({
        // Keep OpenArtifact's network internal. The broker owns and audits public HTTP on misses.
        mode: 'read',
        imageMode: mode,
        reader: mode === 'read' ? store : seed,
        coldAllowed: coldAllowed || mode === 'record',
        reportMiss: mode === 'read' ? reportMiss : () => {},
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
    if (mode === 'read') {
      const observations = cold ? cold.observations : [...misses].map(key => ({key, accessed: Date.now()}));
      if (observations.length) await fs.writeFile(receiptFile, JSON.stringify(observations), {mode: 0o600});
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
// Preserve Node's normal fatal exit. Only emit the error code and source basename/line,
// because raw exception messages can contain signed artifact URLs or credentials.
const diagnosticFiles = new Set(['client-routing.cjs', 'proxy.cjs', 'service.cjs', 'github-store.cjs', 'adapter.cjs',
  'transport.cjs', 'gradle-build-cache.cjs', 'buildkit-cache.cjs', 'events', 'net', 'task_queues', 'readable',
  'writable', 'destroy', '_http_client', '_http_outgoing', '_http_server', 'undici', 'client-h1.js',
  'client.js', 'socket_list', 'http2', 'errors', 'abort_controller', 'webstreams', 'readable_stream']);
process.on('uncaughtExceptionMonitor', (error, origin) => {
  const knownNames = new Map([['Error', 'ERROR'], ['AbortError', 'ABORT'], ['TimeoutError', 'TIMEOUT'],
    ['TypeError', 'TYPE_ERROR'], ['RangeError', 'RANGE_ERROR'], ['AggregateError', 'AGGREGATE_ERROR']]);
  const code = /^(?:E[A-Z0-9_]{1,64}|UND_ERR_[A-Z_]+|ABORT_ERR)$/.test(error?.code)
    ? error.code : knownNames.get(error?.name) || 'UNKNOWN';
  const frames = String(error?.stack || '').split('\n').slice(1)
    .map(frame => /(?:^|[/:])([A-Za-z0-9_.-]+\.(?:cjs|js)|[a-z_]+):(\d+):(\d+)\)?$/.exec(frame))
    // Preserve the position of library frames even when their basename is outside the allowlist.
    .filter(Boolean).slice(0, 8).map(([, file, line]) => `${diagnosticFiles.has(file) ? file : 'library'}:${line}`);
  require('node:fs').writeSync(2, `Broker fatal ${origin} ${code} ${frames.join(' ')}\n`);
});
service(mode, dir).catch(async (error) => {
  await fs.writeFile(path.join(dir, 'failed'), error.message);
  process.exitCode = 1;
});
