'use strict';

const {randomBytes, timingSafeEqual} = require('node:crypto');
const {SignatureV4} = require('@smithy/signature-v4');
const {Hash} = require('@smithy/core/serde');
const {fault} = require('./storage.cjs');

// These are credentials for this loopback store only. The real GH/AWS clients live in the parent process.
function jobSigner(credentials, region) {
  return new SignatureV4({
    credentials,
    region,
    service: 's3',
    sha256: Hash.bind(null, 'sha256'),
    uriEscapePath: false,
  });
}
function jobAuth({
  region = 'us-west-2',
  credentials = {
    accessKeyId: `LOCAL${randomBytes(12).toString('hex')}`,
    secretAccessKey: randomBytes(32).toString('base64'),
  },
} = {}) {
  if (
    !/^[\w-]{1,40}$/.test(region) ||
    !/^[\w-]{1,128}$/.test(credentials.accessKeyId) ||
    typeof credentials.secretAccessKey !== 'string' ||
    credentials.secretAccessKey.length < 16
  )
    throw new Error('Invalid job storage credentials');
  const signer = jobSigner(credentials, region);
  async function authorize(req, url) {
    const auth = req.headers.authorization || '';
    const match =
      /^AWS4-HMAC-SHA256 Credential=([^/]+)\/(\d{8})\/([^/]+)\/s3\/aws4_request,\s*SignedHeaders=([a-z0-9;-]+),\s*Signature=([a-f0-9]{64})$/.exec(
        auth,
      );
    const date = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(req.headers['x-amz-date'] || '');
    if (
      !match ||
      !date ||
      match[1] !== credentials.accessKeyId ||
      match[3] !== region ||
      match[2] !== date.slice(1, 4).join('')
    )
      throw fault(403, 'AccessDenied');
    const time = new Date(`${date[1]}-${date[2]}-${date[3]}T${date[4]}:${date[5]}:${date[6]}Z`);
    if (!Number.isFinite(+time) || Math.abs(Date.now() - time) > 15 * 60 * 1000)
      throw fault(403, 'RequestTimeTooSkewed');
    const signed = new Set(match[4].split(';'));
    if (
      !signed.has('host') ||
      !signed.has('x-amz-date') ||
      [...signed].some((k) => !Object.hasOwn(req.headers, k))
    )
      throw fault(403, 'SignatureDoesNotMatch');
    // The upstream Go signer puts x-amz-content-sha256 in the canonical payload hash even when it is not a signed header.
    const headers = Object.fromEntries(
      Object.entries(req.headers).filter(([name]) => signed.has(name) || name === 'x-amz-content-sha256'),
    );
    if (!/^(?:[a-f0-9]{64}|UNSIGNED-PAYLOAD)$/.test(headers['x-amz-content-sha256'] || ''))
      throw fault(403, 'AccessDenied');
    if (
      Object.keys(req.headers).some(
        (name) => name.startsWith('x-amz-') && name !== 'x-amz-content-sha256' && !signed.has(name),
      )
    )
      throw fault(403, 'SignatureDoesNotMatch');
    const query = Object.create(null);
    for (const key of url.searchParams.keys()) {
      const values = url.searchParams.getAll(key);
      query[key] = values.length === 1 ? values[0] : values;
    }
    const expected = await signer.sign(
      {method: req.method, protocol: 'http:', hostname: 'localhost', path: url.pathname, query, headers},
      {
        signingDate: time,
        unsignableHeaders: new Set(Object.keys(headers).filter((k) => !signed.has(k))),
        signableHeaders: signed,
      },
    );
    const signature = /SignedHeaders=([^,]+), Signature=([a-f0-9]{64})$/.exec(expected.headers.authorization);
    if (
      !signature ||
      signature[1] !== match[4] ||
      !timingSafeEqual(Buffer.from(signature[2], 'hex'), Buffer.from(match[5], 'hex'))
    )
      throw fault(403, 'SignatureDoesNotMatch');
    return headers['x-amz-content-sha256'];
  }
  return {region, credentials, authorize};
}
module.exports = {jobAuth, jobSigner};
