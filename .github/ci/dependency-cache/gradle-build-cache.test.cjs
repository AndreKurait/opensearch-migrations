'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { openBuildCache, inventory } = require('./gradle-build-cache.cjs');

test('a fresh PR fetches a trusted task output, verifies bytes, and rejects writes', async (t) => {
  const workspace = await fs.mkdtemp(path.join(os.tmpdir(), 'gradle-cache-check-'));
  const archives = new Map(),
    catalog = new Map();
  t.after(() => fs.rm(workspace, { recursive: true, force: true }));
  const toolkit = {
    async saveCache([member], key) {
      archives.set(key, await fs.readFile(path.join(workspace, member)));
      return 1;
    },
    async restoreCache([member], key, _, opts) {
      if (!archives.has(key)) return;
      await fs.writeFile(opts.destination, archives.get(key));
      return key;
    },
  };
  const task = '123456789abcdef0'.repeat(2);
  async function start(mode) {
    const store = await openBuildCache({
      mode,
      trusted: mode === 'record',
      toolkit,
      getInventory: async () => catalog,
      workspace,
    });
    const server = createServer((req, res) => store.handle(req, res));
    await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
    t.after(async () => {
      server.closeAllConnections();
      await new Promise((ok) => server.close(ok));
      await store.close();
    });
    return `http://127.0.0.1:${server.address().port}/gradle-build-cache/${task}`;
  }
  const writer = await start('record');
  assert.equal((await fetch(writer)).status, 404);
  assert.equal((await fetch(writer, { method: 'PUT', body: 'compiled task output' })).status, 200);
  const reader = await start('read');
  assert.equal(await (await fetch(reader)).text(), 'compiled task output');
  assert.equal((await fetch(reader, { method: 'PUT', body: 'replacement' })).status, 403);
  assert.equal(archives.size, 1);
  archives.set([...archives.keys()][0], Buffer.from('corrupt'));
  assert.equal((await fetch(await start('read'))).status, 502);
});

test('Gradle discovery accepts only content hashes advertised by main', async () => {
  const task = 'a'.repeat(32),
    digest = 'b'.repeat(64),
    bad = 'c'.repeat(64);
  const keys = await inventory('example/repo', undefined, async (url) => {
    assert.equal(url.searchParams.get('ref'), 'refs/heads/main');
    return Response.json({
      actions_caches: [
        { key: `migrations-gradle-output-v1-${task}-${bad}`, ref: 'refs/pull/123/merge' },
        { key: `migrations-gradle-output-v1-${task}-${digest}`, ref: 'refs/heads/main' },
      ],
    });
  });
  assert.equal(keys.get(task), digest);
});
