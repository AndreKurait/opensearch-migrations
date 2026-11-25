'use strict';

const http = require('node:http');
const {createReadStream} = require('node:fs');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pipeline} = require('node:stream/promises');
const {openStorage, blobKey, keyDigest, fault} = require('./storage.cjs');
const {jobAuth} = require('./adapter-auth.cjs');
const {createHash} = require('node:crypto');
const ns = 'http://s3.amazonaws.com/doc/2006-03-01/';
const escape = (value) =>
  String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
const unescapeETag = (value) => value.trim().replaceAll('&quot;', '"').replaceAll('&#34;', '"');
function tag(name, value) {
  return `<${name}>${escape(value)}</${name}>`;
}
function xml(res, status, body) {
  res.writeHead(status, {'Content-Type': 'application/xml'});
  res.end(body);
}
function metadata(headers) {
  return Object.fromEntries(
    Object.entries(headers)
      .filter(([k]) => /^x-amz-meta-[a-z0-9_-]{1,64}$/.test(k))
      .map(([k, v]) => [k.slice(11), String(v)]),
  );
}
function range(value, size) {
  if (!value) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2]) || !size) throw fault(416, 'InvalidRange');
  const start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
  const end = match[1] && match[2] ? Math.min(size - 1, Number(match[2])) : size - 1;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end)
    throw fault(416, 'InvalidRange');
  return {start, end};
}
async function smallBody(req) {
  let n = 0;
  const parts = [];
  for await (const part of req) {
    n += part.length;
    if (n > 2 * 1024 * 1024) throw fault(413, 'EntityTooLarge');
    parts.push(part);
  }
  return Buffer.concat(parts).toString('utf8');
}
function completeParts(body) {
  if (/<!|<\?[^x]/.test(body) || !/<CompleteMultipartUpload(?:\s[^>]*)?>/.test(body))
    throw fault(400, 'MalformedXML');
  return Array.from(body.matchAll(/<Part>([\s\S]*?)<\/Part>/g), ([_, part]) => {
    const n = /<PartNumber>(\d+)<\/PartNumber>/.exec(part);
    const e = /<ETag>([^<]+)<\/ETag>/.exec(part);
    if (!n || !e) throw fault(400, 'MalformedXML');
    return {number: Number(n[1]), etag: unescapeETag(e[1])};
  });
}
function boundedCount(value) {
  return Math.max(1, Math.min(1000, Number(value) || 1000));
}
async function startAdapter(options = {}) {
  const bucket = options.bucket || 'openartifact';
  if (!/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(bucket) || (options.host && options.host !== '127.0.0.1')) {
    throw new Error('OpenArtifact adapter requires one job-local bucket and IPv4 loopback');
  }
  const {region, credentials, authorize} = jobAuth(options);
  const store = await openStorage(options);
  let active = 0;
  const count = {};
  const metrics = Object.assign(store.metrics, {operations: count, errors: {}});
  const op = (name) => {
    count[name] = (count[name] || 0) + 1;
  };
  async function handle(req, res) {
    if (active > (options.maxConcurrency || 48)) throw fault(429, 'SlowDown');
    // Only path-style requests to this one local bucket. Never treat a URL or S3 header as a remote target.
    if (!req.url.startsWith('/') || req.url.startsWith('//')) throw fault(400, 'InvalidRequest');
    const url = new URL(req.url, 'http://localhost');
    const q = url.searchParams;
    const signedHash = await authorize(req, url);
    const bodyHash = signedHash === 'UNSIGNED-PAYLOAD' ? undefined : signedHash;
    if (url.pathname !== `/${bucket}` && !url.pathname.startsWith(`/${bucket}/`))
      throw fault(404, 'NoSuchBucket');
    const key = decodeURIComponent(url.pathname.slice(bucket.length + 2));
    const method = req.method;
    const finish = (status = 200, headers = {}) => {
      res.writeHead(status, headers);
      res.end();
    };
    if (!key) {
      if (method === 'HEAD') {
        op('HeadBucket');
        return finish();
      }
      if (method !== 'GET') throw fault(405, 'MethodNotAllowed');
      const prefix = q.get('prefix') || '';
      if (q.has('uploads')) {
        op('ListMultipartUploads');
        const rows = [...store.uploads.values()].filter((u) => u.key.startsWith(prefix));
        return xml(
          res,
          200,
          `<ListMultipartUploadsResult xmlns="${ns}">${tag('Bucket', bucket)}<IsTruncated>false</IsTruncated>${rows.map((u) => `<Upload>${tag('Key', u.key)}${tag('UploadId', u.id)}${tag('Initiated', u.started.toISOString())}</Upload>`).join('')}</ListMultipartUploadsResult>`,
        );
      }
      if (q.get('list-type') !== '2') throw fault(400, 'InvalidRequest');
      op('ListObjectsV2');
      const start = q.get('continuation-token') || q.get('start-after') || '';
      const keys = store.list().filter((k) => k.startsWith(prefix) && k > start);
      const max = boundedCount(q.get('max-keys'));
      const subset = keys.slice(0, max);
      const rows = subset.map((k) => {
        const item = store.stat(k);
        return `<Contents>${tag('Key', k)}${tag('LastModified', item.modified.toISOString())}${tag('ETag', item.etag)}${tag('Size', item.size)}<StorageClass>STANDARD</StorageClass></Contents>`;
      });
      return xml(
        res,
        200,
        `<ListBucketResult xmlns="${ns}">${tag('Name', bucket)}${tag('Prefix', prefix)}${tag('KeyCount', subset.length)}${tag('MaxKeys', max)}${tag('IsTruncated', keys.length > max)}${keys.length > max ? tag('NextContinuationToken', subset.at(-1)) : ''}${rows.join('')}</ListBucketResult>`,
      );
    }
    keyDigest(key);
    const id = q.get('uploadId');
    if (method === 'POST' && q.has('uploads')) {
      op('CreateMultipartUpload');
      return xml(
        res,
        200,
        `<InitiateMultipartUploadResult xmlns="${ns}">${tag('Bucket', bucket)}${tag('Key', key)}${tag('UploadId', store.begin(key, metadata(req.headers)))}</InitiateMultipartUploadResult>`,
      );
    }
    if (id) {
      const u = store.upload(key, id);
      if (method === 'PUT') {
        op('UploadPart');
        const p = await store.part(key, id, Number(q.get('partNumber')), req, bodyHash);
        return finish(200, {ETag: p.etag});
      }
      if (method === 'POST') {
        op('CompleteMultipartUpload');
        const body = await smallBody(req);
        if (bodyHash && createHash('sha256').update(body).digest('hex') !== bodyHash)
          throw fault(400, 'BadDigest');
        const p = await store.complete(key, id, completeParts(body));
        return xml(
          res,
          200,
          `<CompleteMultipartUploadResult xmlns="${ns}">${tag('Bucket', bucket)}${tag('Key', key)}${tag('ETag', p.etag)}</CompleteMultipartUploadResult>`,
        );
      }
      if (method === 'DELETE') {
        op('AbortMultipartUpload');
        await store.abort(key, id);
        return finish(204);
      }
      if (method === 'GET') {
        op('ListParts');
        const marker = Number(q.get('part-number-marker') || 0);
        const limit = boundedCount(q.get('max-parts'));
        const all = [...u.parts.entries()].sort(([a], [b]) => a - b).filter(([n]) => n > marker),
          slice = all.slice(0, limit);
        const parts = slice
          .map(
            ([n, v]) =>
              `<Part>${tag('PartNumber', n)}${tag('ETag', v.etag)}${tag('LastModified', v.modified.toISOString())}${tag('Size', v.size)}</Part>`,
          )
          .join('');
        return xml(
          res,
          200,
          `<ListPartsResult xmlns="${ns}">${tag('Bucket', bucket)}${tag('Key', key)}${tag('UploadId', id)}${tag('IsTruncated', all.length > limit)}${all.length > limit ? tag('NextPartNumberMarker', slice.at(-1)[0]) : ''}${parts}</ListPartsResult>`,
        );
      }
      throw fault(405, 'MethodNotAllowed');
    }
    if (method === 'PUT') {
      if (req.headers['x-amz-copy-source']) {
        op('CopyObject');
        const src = decodeURIComponent(req.headers['x-amz-copy-source']).replace(/^\//, '');
        if (!src.startsWith(bucket + '/')) throw fault(403, 'AccessDenied');
        const source = await store.get(src.slice(bucket.length + 1));
        if (
          req.headers['x-amz-copy-source-if-match'] &&
          req.headers['x-amz-copy-source-if-match'] !== source.etag
        )
          throw fault(412, 'PreconditionFailed');
        const file = await store.put(key, createReadStream(source.path), metadata(req.headers));
        return xml(
          res,
          200,
          `<CopyObjectResult xmlns="${ns}">${tag('LastModified', file.modified.toISOString())}${tag('ETag', file.etag)}</CopyObjectResult>`,
        );
      }
      op('PutObject');
      const file = await store.put(key, req, metadata(req.headers), bodyHash);
      return finish(200, {ETag: file.etag});
    }
    if (method === 'DELETE') {
      op('DeleteObject');
      await store.remove(key);
      return finish(204);
    }
    if (method !== 'HEAD' && method !== 'GET') throw fault(405, 'MethodNotAllowed');
    op(method === 'HEAD' ? 'HeadObject' : 'GetObject');
    const item = store.stat(key);
    if (!item) throw fault(404, 'NoSuchKey');
    if (req.headers['if-match'] && req.headers['if-match'] !== item.etag)
      throw fault(412, 'PreconditionFailed');
    if (req.headers['if-none-match'] === item.etag) return finish(304);
    const requested = range(req.headers.range, item.size);
    const size = requested ? requested.end - requested.start + 1 : item.size;
    const headers = {
      'Content-Length': size,
      ETag: item.etag,
      'Last-Modified': item.modified.toUTCString(),
      'Accept-Ranges': 'bytes',
      'Content-Type': 'application/octet-stream',
      ...Object.fromEntries(Object.entries(item.meta || {}).map(([k, v]) => ['x-amz-meta-' + k, v])),
    };
    if (requested) headers['Content-Range'] = `bytes ${requested.start}-${requested.end}/${item.size}`;
    if (method === 'HEAD') {
      metrics.heads++;
      return finish(requested ? 206 : 200, headers);
    }
    metrics.gets++;
    const file = await store.get(key);
    res.writeHead(requested ? 206 : 200, headers);
    await pipeline(createReadStream(file.path, requested || {}), res);
    metrics.bytesServed += size;
  }
  const server = http.createServer({maxHeaderSize: 32768}, (req, res) => {
    active++;
    handle(req, res)
      .catch((error) => {
        const code = error.status ? error.code : 'InternalError';
        metrics.errors[code] = (metrics.errors[code] || 0) + 1;
        if (res.headersSent) {
          res.destroy();
          return;
        }
        xml(res, error.status || 500, `<Error>${tag('Code', code)}${tag('Message', code)}</Error>`);
      })
      .finally(() => active--);
  });
  server.requestTimeout = 120000;
  server.headersTimeout = 15000;
  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(options.port || 0, '127.0.0.1', resolve);
    });
  } catch (error) {
    await store.close();
    throw error;
  }
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    bucket,
    region,
    credentials,
    metrics,
    // Trusted parent can export an observed blob from this job's overlay without using the network.
    async materialize(entry, destination) {
      if (!entry || !path.isAbsolute(destination)) throw fault(400, 'InvalidExport');
      const file = await store.get(blobKey(entry.digest));
      if (entry.size !== file.size) throw fault(502, 'BackingObjectMismatch');
      await fs.copyFile(file.path, destination, fs.constants.COPYFILE_EXCL);
    },
    async close() {
      const closing = new Promise((resolve) => server.close(resolve));
      server.closeIdleConnections();
      await closing;
      await store.close();
    },
  };
}
module.exports = {startAdapter};
