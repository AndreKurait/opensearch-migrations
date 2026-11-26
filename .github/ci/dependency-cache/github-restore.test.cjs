'use strict';

const assert = require('node:assert/strict');
const {execFile} = require('node:child_process');
const {createReadStream} = require('node:fs');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {promisify} = require('node:util');
const test = require('node:test');
const {extractOne, safeRestore} = require('./github-restore.cjs');
const run = promisify(execFile);
const signed = 'https://cache.example.invalid/download';

async function fixture(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'checked-cache-archive-'));
  t.after(() => fs.rm(dir, {recursive: true, force: true}));
  const member = `.migrations-public-dependency-cache-v1/blobs/object-${'a'.repeat(64)}`;
  await fs.mkdir(path.dirname(path.join(dir, member)), {recursive: true});
  await fs.writeFile(path.join(dir, member), 'ordinary public cache bytes');
  await fs.writeFile(path.join(dir, 'extra'), 'second regular file');
  async function archive(names, output) {
    await run('tar', ['--posix', '-C', dir, '--use-compress-program=zstd -q', '-cf', output, ...names]);
  }
  const source = path.join(dir, 'fixture.tar.zst');
  await archive([member], source);
  return {dir, member, archive, source};
}

test('stream valid GNU POSIX/zstd archives with a PAX filename into a new private file', async (t) => {
  const {dir, member, source} = await fixture(t);
  const dest = path.join(dir, 'public.bin');
  const dump = await fs.readFile(source);
  const key = 'exact-public-key';
  let options;
  const matched = await safeRestore([member], key, [], {
    destination: dest,
    maxBytes: 27,
    download: async () => ({ok: true, matchedKey: key, signedDownloadUrl: signed}),
    request: async (url, opts) => {
      assert.equal(url.toString(), signed);
      options = opts;
      return new Response(dump);
    },
  });
  assert.equal(matched, key);
  assert.equal(options.redirect, 'error');
  assert.equal(await fs.readFile(dest, 'utf8'), 'ordinary public cache bytes');
  assert.equal((await fs.stat(dest)).mode & 0o777, 0o600);
});

test('reject extra members, unexpected names, links, and oversized regular files before exposing any destination', async (t) => {
  const {dir, member, archive, source} = await fixture(t);
  const dest = path.join(dir, 'output');
  await assert.rejects(extractOne(createReadStream(source), member, dest, 4));
  await assert.rejects(extractOne(createReadStream(source), 'different', dest, 100));
  for (const names of [
    [member, 'extra'],
    ['extra', member],
    [member, member],
  ]) {
    await archive(names, source);
    await assert.rejects(extractOne(createReadStream(source), member, dest, 100));
  }
  await fs.symlink('extra', path.join(dir, 'link'));
  await archive(['link'], source);
  await assert.rejects(extractOne(createReadStream(source), 'link', dest, 100));
  await assert.rejects(fs.stat(dest), {code: 'ENOENT'});
});

test('existing destination is never overwritten and mismatch/lookup-only never downloads', async (t) => {
  const {dir, member, source} = await fixture(t);
  const dest = path.join(dir, 'keep');
  await fs.writeFile(dest, 'keep');
  await assert.rejects(extractOne(createReadStream(source), member, dest, 100), {code: 'EEXIST'});
  assert.equal(await fs.readFile(dest, 'utf8'), 'keep');
  const lookup = {
    download: async () => ({ok: true, matchedKey: 'other', signedDownloadUrl: signed}),
    request: () => assert.fail('Should not fetch'),
  };
  assert.equal(await safeRestore([member], 'wanted', [], lookup), undefined);
  assert.equal(await safeRestore([member], 'other', [], {...lookup, lookupOnly: true}), 'other');
});


test('archive failure reports only backend hostname and HTTP status', async (t) => {
  const {dir, member} = await fixture(t);
  const log = t.mock.method(console, 'warn', () => {});
  const secret = 'fixture-signature-never-logged';
  await assert.rejects(safeRestore([member], 'wanted', [], {
    destination: path.join(dir, 'dest'),
    download: async () => ({ok: true, matchedKey: 'wanted', signedDownloadUrl: `${signed}?sig=${secret}`}),
    request: async () => new Response('denied', {status: 403}),
  }), {message: 'Cache archive at cache.example.invalid failed (HTTP_403,Error).'});
  assert.equal(log.mock.calls.length, 1);
  assert.equal(log.mock.calls[0].arguments[0], 'Cache archive at cache.example.invalid failed (HTTP_403,Error).');
});

test('an archive response interrupted before extraction rejects only that restore', async (t) => {
  const {dir, member} = await fixture(t);
  const destination = path.join(dir, 'dest');
  await assert.rejects(safeRestore([member], 'wanted', [], {
    destination,
    download: async () => ({ok: true, matchedKey: 'wanted', signedDownloadUrl: signed}),
    request: async () => new Response(new ReadableStream({
      start(controller) { controller.error(new TypeError('terminated')); },
    })),
  }), /Cache archive at cache.example.invalid failed/);
  await assert.rejects(fs.stat(destination), {code: 'ENOENT'});
});

test('retries an interrupted cache archive and verifies the replacement stream', async (t) => {
  const {dir, member, source} = await fixture(t);
  const destination = path.join(dir, 'restored');
  const data = await fs.readFile(source);
  let attempts = 0;
  await safeRestore([member], 'wanted', [], {
    destination,
    download: async () => ({ok: true, matchedKey: 'wanted', signedDownloadUrl: signed}),
    request: async () => new Response(++attempts === 1 ? data.subarray(0, 12) : data),
  });
  assert.equal(attempts, 2);
  assert.equal(await fs.readFile(destination, 'utf8'), 'ordinary public cache bytes');
});
