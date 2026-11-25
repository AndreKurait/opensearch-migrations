'use strict';

const {beforeEach, afterEach, test} = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {setTimeout: sleep} = require('node:timers/promises');
const {jobSigner} = require('./adapter-auth.cjs');
const {startAdapter} = require('./adapter.cjs');
const {blobKey} = require('./storage.cjs');
const sum = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const temporary = () => 'staging/' + crypto.randomUUID();
let dir,
  open = [];
beforeEach(async () => {
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'oa-adapter-test-'));
});
afterEach(async () => {
  await Promise.all(open.map((a) => a.close()));
  open = [];
  await fs.rm(dir, {recursive: true, force: true});
});
async function setup(options = {}) {
  const adapter = await startAdapter({...options, tempDirectory: dir});
  open.push(adapter);
  const signer = jobSigner(adapter.credentials, adapter.region);
  async function send(method, key = '', {query = {}, headers = {}, body, transmittedBody, signingDate} = {}) {
    const u = new URL(adapter.url + '/' + adapter.bucket + '/' + key);
    for (const [k, v] of Object.entries(query)) u.searchParams.set(k, v);
    const signed = await signer.sign(
      {
        hostname: u.hostname,
        path: u.pathname,
        method,
        protocol: u.protocol,
        query,
        headers: {host: u.host, ...headers},
        body,
      },
      {signingDate},
    );
    return fetch(u, {method, headers: signed.headers, body: transmittedBody ?? body});
  }
  return {adapter, send};
}

test('HEAD reads signed catalog metadata; concurrent ranges restore the verified content once', async () => {
  const bytes = Buffer.from('0123456789');
  const digest = sum(bytes);
  const key = blobKey(digest);
  let restored = 0;
  const {adapter, send} = await setup({
    entries: [{key: 'fixture', size: bytes.length, digest}],
    materialize: async (entry, destination) => {
      restored++;
      await sleep(40);
      await fs.writeFile(destination, bytes);
    },
  });
  assert.equal((await fetch(adapter.url + '/' + adapter.bucket + '/' + key)).status, 403);
  const head = await send('HEAD', key);
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-length'), '10');
  assert.equal(restored, 0);
  const responses = await Promise.all([
    send('GET', key, {headers: {range: 'bytes=3-6'}}),
    send('GET', key, {headers: {range: 'bytes=-3'}}),
  ]);
  assert.deepEqual(
    responses.map((x) => x.status),
    [206, 206],
  );
  assert.equal(responses[0].headers.get('content-range'), 'bytes 3-6/10');
  assert.deepEqual(await Promise.all(responses.map((r) => r.text())), ['3456', '789']);
  assert.equal(restored, 1);
  assert.equal(adapter.metrics.queuedReads, 1);
  assert.equal(adapter.metrics.bytesServed, 7);
  assert.equal(adapter.metrics.verifiedBytes, 10);
  assert.equal((await send('GET', key, {headers: {range: 'bytes=10-'}})).status, 416);
});
test('denies cross-job signatures, stale requests, cross-bucket copies and altered signed bodies', async () => {
  const {adapter, send} = await setup();
  const key = temporary();
  const uri = adapter.url + '/' + adapter.bucket + '/' + key;
  assert.equal((await send('PUT', key, {body: 'one', transmittedBody: 'two'})).status, 400);
  assert.equal((await send('HEAD', key)).status, 404);
  assert.equal((await send('HEAD', key, {signingDate: new Date(0)})).status, 403);
  assert.equal((await send('PUT', key, {headers: {'x-amz-copy-source': 'other/' + key}})).status, 403);
  const auth = jobSigner(
    {...adapter.credentials, secretAccessKey: crypto.randomBytes(32).toString('hex')},
    adapter.region,
  );
  const url = new URL(uri);
  const invalid = await auth.sign({
    method: 'GET',
    path: url.pathname,
    query: {},
    hostname: url.hostname,
    protocol: url.protocol,
    headers: {host: url.host},
  });
  assert.equal((await fetch(url, {headers: invalid.headers})).status, 403);
});
test('corrupt restores expose no bytes, permit retry and never hydrate on HEAD', async () => {
  const bytes = Buffer.from('good');
  const digest = sum(bytes);
  const key = blobKey(digest);
  let calls = 0;
  const {send, adapter} = await setup({
    maxBytes: 4,
    entries: [{key: 'p', size: bytes.length, digest}],
    materialize: async (_, dst) => fs.writeFile(dst, ++calls === 1 ? 'evil' : bytes),
  });
  assert.equal((await send('GET', key, {headers: {range: 'bytes=0-0'}})).status, 502);
  assert.equal(adapter.metrics.localBytes, 0);
  assert.equal((await send('HEAD', key)).status, 200);
  assert.equal(calls, 1);
  assert.equal(await (await send('GET', key)).text(), 'good');
});
test('local staging, copy conditions and deletion are isolated from future jobs', async () => {
  const {send} = await setup();
  const bytes = 'work';
  const key = blobKey(sum(bytes));
  const staging = temporary();
  const response = await send('PUT', staging, {body: bytes, headers: {'x-amz-meta-test': 'ok'}});
  assert.equal(response.status, 200);
  const etag = response.headers.get('etag');
  assert.ok(etag);
  const copy = {'x-amz-copy-source': '/openartifact/' + staging};
  assert.equal(
    (await send('PUT', key, {headers: {...copy, 'x-amz-copy-source-if-match': 'wrong'}})).status,
    412,
  );
  assert.equal(
    (await send('PUT', key, {headers: {...copy, 'x-amz-copy-source-if-match': etag}})).status,
    200,
  );
  assert.equal(await (await send('GET', key)).text(), bytes);
  assert.equal((await send('DELETE', staging)).status, 204);
  assert.equal((await send('HEAD', staging)).status, 404);
  assert.equal((await (await setup()).send('HEAD', key)).status, 404);
});
test('multipart preserves part order, ETags, retriable errors, listing and cleanup', async () => {
  const {send, adapter} = await setup();
  const key = blobKey(sum('ab'));
  const start = await send('POST', key, {query: {uploads: ''}});
  const id = /<UploadId>([^<]+)<\/UploadId>/.exec(await start.text())[1];
  const parts = await Promise.all(
    ['a', 'b'].map(async (body, i) => {
      const response = await send('PUT', key, {query: {uploadId: id, partNumber: String(i + 1)}, body});
      return `<Part><PartNumber>${i + 1}</PartNumber><ETag>${response.headers.get('etag')}</ETag></Part>`;
    }),
  );
  const list = await send('GET', key, {query: {uploadId: id}});
  assert.match(await list.text(), /<PartNumber>2<\/PartNumber>/);
  const complete = (parts) =>
    send('POST', key, {
      query: {uploadId: id},
      body: `<CompleteMultipartUpload>${parts.join('')}</CompleteMultipartUpload>`,
    });
  assert.equal((await complete([...parts].reverse())).status, 400);
  assert.equal((await complete(parts)).status, 200);
  assert.equal(await (await send('GET', key)).text(), 'ab');
  assert.equal(adapter.metrics.multipartCompleted, 1);
  assert.equal((await send('GET', key, {query: {uploadId: id}})).status, 404);
  const stage = temporary();
  const init = await send('POST', stage, {query: {uploads: ''}});
  const remove = /<UploadId>([^<]+)<\/UploadId>/.exec(await init.text())[1];
  await send('PUT', stage, {query: {uploadId: remove, partNumber: '1'}, body: 'x'});
  assert.equal((await send('DELETE', stage, {query: {uploadId: remove}})).status, 204);
  assert.equal(adapter.metrics.localBytes, 2);
  assert.equal(adapter.metrics.multipartAborted, 1);
});
test('bounds overlay bytes, oversized restore, staging keys and multipart sessions', async () => {
  const digest = sum('abc');
  let reads = 0;
  const {adapter, send} = await setup({
    maxBytes: 2,
    maxUploads: 1,
    entries: [{key: 'v', size: 3, digest}],
    materialize: async () => {
      reads++;
    },
  });
  assert.equal((await send('GET', blobKey(digest))).status, 507);
  assert.equal(reads, 0);
  assert.equal((await send('PUT', temporary(), {body: 'abc'})).status, 507);
  assert.equal(adapter.metrics.localBytes, 0);
  assert.equal((await send('PUT', temporary(), {body: 'ok'})).status, 200);
  assert.equal((await send('POST', temporary(), {query: {uploads: ''}})).status, 200);
  assert.equal((await send('POST', temporary(), {query: {uploads: ''}})).status, 429);
  assert.equal((await send('GET', 'staging/private')).status, 400);
});

test('trusted parent can export a verified local blob without publishing a URL or replacing existing files', async () => {
  const {adapter, send} = await setup();
  const bytes = 'job-local result';
  const entry = {digest: sum(bytes), size: bytes.length};
  assert.equal((await send('PUT', blobKey(entry.digest), {body: bytes})).status, 200);
  const dest = path.join(dir, 'export');
  await adapter.materialize(entry, dest);
  assert.equal(await fs.readFile(dest, 'utf8'), bytes);
  await assert.rejects(adapter.materialize(entry, dest), {code: 'EEXIST'});
  await assert.rejects(adapter.materialize({...entry, size: 0}, dest), {code: 'BackingObjectMismatch'});
});


test('limits outstanding multipart part files across uploads and releases the allowance on abort', async () => {
  const {send} = await setup({maxParts: 1});
  const key = temporary();
  const init = await send('POST', key, {query: {uploads: ''}});
  const id = /<UploadId>([^<]+)<\/UploadId>/.exec(await init.text())[1];
  const upload = n => send('PUT', key, {query: {uploadId: id, partNumber: String(n)}, body: ''});
  assert.equal((await upload(1)).status, 200);
  assert.equal((await upload(1)).status, 200);
  assert.equal((await upload(2)).status, 507);
  assert.equal((await send('DELETE', key, {query: {uploadId: id}})).status, 204);
  const next = await send('POST', key, {query: {uploads: ''}});
  const nextId = /<UploadId>([^<]+)<\/UploadId>/.exec(await next.text())[1];
  assert.equal((await send('PUT', key, {query: {uploadId: nextId, partNumber: '1'}, body: ''})).status, 200);
});
