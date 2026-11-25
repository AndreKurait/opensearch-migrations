'use strict';

const http = require('node:http');
const {createHash} = require('node:crypto');
const {open, rm, readFile} = require('node:fs/promises');
const {isAbsolute} = require('node:path');
const {Readable, Transform} = require('node:stream');
const {pipeline} = require('node:stream/promises');

const digestPattern = /^[a-f0-9]{64}$/;
const manifestTypes = [
  'application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json',
  'application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json',
  'application/vnd.oci.artifact.manifest.v1+json',
];
const k8sCdn = /^[a-z][a-z0-9-]*-docker\.pkg\.dev$/;
const githubAssets = ['release-assets.githubusercontent.com', 'objects.githubusercontent.com'];
const registries = {
  dockerhub: {
    host: 'registry-1.docker.io', realm: 'https://auth.docker.io/token', service: 'registry.docker.io',
    cdn: ['production.cloudflare.docker.com', 'production.cloudfront.docker.com',
      'docker-images-prod.6aa30f8b08e16409b46e0173d6de2f56.r2.cloudflarestorage.com',
      /^docker-images-prod\.s3\.(dualstack\.)?[a-z0-9-]+\.amazonaws\.com$/],
  },
  ghcr: {host: 'ghcr.io', realm: 'https://ghcr.io/token', service: 'ghcr.io', cdn: ['pkg-containers.githubusercontent.com']},
  quay: {host: 'quay.io', realm: 'https://quay.io/v2/auth', service: 'quay.io', cdn: [/^cdn[0-9]+\.quay\.io$/]},
  ecrpublic: {host: 'public.ecr.aws', realm: 'https://public.ecr.aws/token/', service: 'public.ecr.aws',
    cdn: [/^d[a-z0-9]+\.cloudfront\.net$/]},
  k8s: {host: 'registry.k8s.io', cdn: [k8sCdn, 'prod-registry-k8s-io-eu-central-1.s3.dualstack.eu-central-1.amazonaws.com']},
  gcr: {host: 'mirror.gcr.io', cdn: ['storage.googleapis.com']},
  distroless: {host: 'gcr.io', realm: 'https://gcr.io/v2/token', service: 'gcr.io', repo: 'distroless/static', cdn: []},
};
const rawOrigins = {
  bubblewrap: ['https://security.ubuntu.com/ubuntu/pool/main/b/bubblewrap/', []],
  jdk: ['https://download.java.net/', []],
  corretto: ['https://corretto.aws/downloads/', []],
  k3s: ['https://github.com/k3s-io/k3s/releases/download/', githubAssets],
  kind: ['https://github.com/kubernetes-sigs/kind/releases/download/', githubAssets],
  crane: ['https://github.com/google/go-containerregistry/releases/download/', githubAssets],
  argo: ['https://github.com/argoproj/argo-workflows/releases/download/', githubAssets],
  kubectl: ['https://dl.k8s.io/', ['cdn.dl.k8s.io']],
  helm: ['https://get.helm.sh/', []],
  gradle: ['https://services.gradle.org/distributions/', ['downloads.gradle.org', 'github.com', ...githubAssets]],
  temurin: ['https://github.com/adoptium/temurin21-binaries/releases/download/', githubAssets],
  elastic: ['https://artifacts.elastic.co/', []],
};
const mavenOrigins = ['https://repo.maven.apache.org/maven2/', 'https://plugins.gradle.org/m2/'];
const mavenCdn = ['plugins-artifacts.gradle.org', 'repo.maven.apache.org', 'repo1.maven.org'];

function canonicalKey(key) {
  if (typeof key === 'string' && /^\/npm\/@[a-z0-9._-]+%2f[a-z0-9._-]+$/i.test(key)) return key.replace(/%2f/i, '/');
  if (typeof key === 'string' && /^\/pypi\/simple\/[a-z0-9._-]+\/$/i.test(key)) return key.slice(0, -1);
  return key;
}

function parseKey(key) {
  if (typeof key !== 'string' || key.length > 2048 || !key.startsWith('/')) throw new Error('Invalid artifact key');
  const parts = key.slice(1).split('/');
  if (parts.some(part => !/^[A-Za-z0-9._+@:-]+$/.test(part) || part === '.' || part === '..')) {
    throw new Error('Invalid artifact path');
  }
  const [kind, originId] = parts;
  if (kind === 'npm') {
    const scoped = /^@[a-z0-9._-]+$/.test(parts[1]);
    const packageLength = scoped ? 3 : 2;
    if (!/^[a-z0-9][a-z0-9._-]*$/.test(parts[packageLength - 1] || '')) throw new Error('Invalid npm package');
    const metadata = parts.length === packageLength;
    const tarball = parts.length === packageLength + 2 && parts[packageLength] === '-' &&
      /^[A-Za-z0-9._+-]+\.tgz$/.test(parts.at(-1));
    if (!(metadata || tarball)) throw new Error('Invalid npm path');
    return {urls: [new URL('./' + parts.slice(1).join('/'), 'https://registry.npmjs.org/')], cdn: [], npmMetadata: metadata};
  }
  if (kind === 'pypi' && originId === 'simple' && parts.length === 3 && /^[a-z0-9][a-z0-9._-]*$/i.test(parts[2])) {
    return {urls: [new URL(`https://pypi.org/simple/${parts[2]}/`)], cdn: [], pypiMetadata: true};
  }
  if (kind === 'pypi' && originId === 'packages' && parts.length >= 4) {
    return {urls: [new URL('./' + parts.slice(1).join('/'), 'https://files.pythonhosted.org/')], cdn: []};
  }
  if (kind === 'maven' && parts.length >= 2) {
    return {urls: mavenOrigins.map(origin => new URL('./' + parts.slice(1).join('/'), origin)), cdn: mavenCdn};
  }
  if (kind === 'raw' && Object.hasOwn(rawOrigins, originId) && parts.length >= 3) {
    const [origin, cdn] = rawOrigins[originId];
    return {urls: [new URL('./' + parts.slice(2).join('/'), origin)], cdn};
  }
  if (kind !== 'v2' || !Object.hasOwn(registries, originId) || parts.length < 5) throw new Error('Unsupported artifact route');
  const operation = parts.at(-2);
  const ref = parts.at(-1);
  const repos = parts.slice(2, -2);
  if (repos.some(part => !/^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*$/.test(part))) throw new Error('Invalid OCI repository');
  const digest = /^sha256:([a-f0-9]{64})$/.exec(ref)?.[1];
  if (!((operation === 'blobs' && digest) ||
        (operation === 'manifests' && (digest || /^[\w][\w.-]{0,127}$/.test(ref))))) throw new Error('Invalid OCI reference');
  const registry = registries[originId];
  if (originId === 'dockerhub' && repos.length === 1) repos.unshift('library');
  const repo = repos.join('/');
  if (registry.repo && registry.repo !== repo) throw new Error('Unsupported public OCI repository');
  return {urls: [new URL(`https://${registry.host}/v2/${repo}/${operation}/${ref}`)], cdn: registry.cdn, registry,
    repo, digest, manifest: operation === 'manifests', oci: true};
}

function validateKey(key) {
  parseKey(key);
  return key;
}

function allowed(url, hosts) {
  return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
    hosts.some(host => typeof host === 'string' ? host === url.hostname : host.test(url.hostname));
}

async function request(url, cdn, headers = {}) {
  const hosts = [url.hostname, ...cdn];
  let current = url;
  for (let count = 0; count <= 5; count++) {
    if (!allowed(current, hosts)) throw new Error('Unapproved upstream redirect');
    const outgoing = {...headers, 'accept-encoding': 'identity'};
    if (current.origin !== url.origin) delete outgoing.authorization;
    const response = await fetch(current, {headers: outgoing, redirect: 'manual', signal: AbortSignal.timeout(600_000)});
    if (![301, 302, 303, 307, 308].includes(response.status)) return response;
    const location = response.headers.get('location');
    await response.body?.cancel();
    if (!location) throw new Error('Upstream redirect has no location');
    current = new URL(location, current);
  }
  throw new Error('Too many upstream redirects');
}

async function anonymousToken(route, challenge) {
  if (!/^Bearer /i.test(challenge || '')) throw new Error('Unsupported registry authentication');
  const fields = Object.fromEntries([...challenge.matchAll(/([A-Za-z]+)="([^"]*)"/g)].map(match => match.slice(1)));
  const {registry, repo} = route;
  if (!registry.realm || fields.realm !== registry.realm || fields.service !== registry.service) {
    throw new Error('Unexpected public registry authentication realm');
  }
  const scope = registry.host === 'public.ecr.aws' ? 'aws' : `repository:${repo}:pull`;
  if (fields.scope && fields.scope !== scope) throw new Error('Unexpected public registry token scope');
  const url = new URL(registry.realm);
  url.searchParams.set('service', registry.service);
  url.searchParams.set('scope', scope);
  const response = await request(url, []);
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error(`Public registry token request returned ${response.status}`);
  }
  let body = '';
  for await (const chunk of response.body) {
    body += Buffer.from(chunk).toString();
    if (body.length > 65536) throw new Error('Oversized registry token response');
  }
  const data = JSON.parse(body);
  const token = data.token || data.access_token;
  if (typeof token !== 'string' || !/^[\x21-\x7e]+$/.test(token)) throw new Error('Invalid public registry token');
  return token;
}

async function packageIndex(route, response) {
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > 32 * 1024 * 1024) throw new Error('Package index exceeds the supported size');
    chunks.push(chunk);
  }
  const length = response.headers.get('content-length');
  if (length !== null && BigInt(length) !== BigInt(size)) throw new Error('Package index size mismatch');
  const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  function localFile(link, origin, prefix) {
    const url = new URL(link);
    if (!allowed(url, [origin]) || url.search || url.hash) throw new Error('Unsupported package download URL');
    const key = prefix + url.pathname;
    validateKey(key);
    return key;
  }
  if (route.npmMetadata) {
    for (const version of Object.values(data.versions || {})) {
      if (version.dist?.tarball) version.dist.tarball = localFile(version.dist.tarball, 'registry.npmjs.org', '/npm');
    }
  } else {
    if (!Array.isArray(data.files)) throw new Error('Invalid Python simple index');
    for (const file of data.files) file.url = localFile(file.url, 'files.pythonhosted.org', '/pypi');
  }
  return Buffer.from(JSON.stringify(data));
}

async function fetchPublic(key, destination, {maxBytes = Infinity} = {}) {
  const route = parseKey(key);
  const headers = {};
  if (route.manifest) headers.accept = manifestTypes.join(', ');
  if (route.npmMetadata) headers.accept = 'application/vnd.npm.install-v1+json';
  if (route.pypiMetadata) headers.accept = 'application/vnd.pypi.simple.v1+json';
  for (const url of route.urls) {
    let response = await request(url, route.cdn, headers);
    if (response.status === 401 && route.registry) {
      const challenge = response.headers.get('www-authenticate');
      await response.body?.cancel();
      const token = await anonymousToken(route, challenge);
      response = await request(url, route.cdn, {...headers, authorization: `Bearer ${token}`});
    }
    if (response.status === 404) {
      await response.body?.cancel();
      continue;
    }
    if (response.status !== 200 || !response.body) {
      await response.body?.cancel();
      throw new Error(`Artifact upstream returned ${response.status}`);
    }
    const type = response.headers.get('content-type')?.split(';')[0].trim() || 'application/octet-stream';
    const announced = response.headers.get('docker-content-digest');
    const length = response.headers.get('content-length');
    if ((route.manifest && !manifestTypes.includes(type)) || (announced && !/^sha256:[a-f0-9]{64}$/.test(announced)) ||
        (response.headers.get('content-encoding') || 'identity').toLowerCase() !== 'identity' ||
        (length !== null && !/^\d+$/.test(length))) {
      await response.body.cancel();
      throw new Error('Invalid upstream artifact headers');
    }
    const hash = createHash('sha256');
    let size = 0;
    const meter = new Transform({transform(chunk, encoding, callback) {
      size += chunk.length;
      if (size > maxBytes) { callback(new Error('Cold dependency exceeds the job download limit')); return; }
      hash.update(chunk);
      callback(null, chunk);
    }});
    if (Number.isFinite(maxBytes) && length !== null && BigInt(length) > BigInt(maxBytes)) {
      await response.body.cancel();
      throw new Error('Cold dependency exceeds the job download limit');
    }
    // The recorder supplies a fresh temporary path. Do not truncate a pre-existing object.
    let file;
    try {
      file = await open(destination, 'wx', 0o600);
      const index = route.npmMetadata || route.pypiMetadata;
      const input = index ? Readable.from([await packageIndex(route, response)]) : Readable.fromWeb(response.body);
      await pipeline(input, meter, file.createWriteStream());
      const digest = hash.digest('hex');
      if ((!index && length !== null && BigInt(length) !== BigInt(size)) || (route.digest && route.digest !== digest) ||
          (announced && announced !== `sha256:${digest}`)) throw new Error('Artifact digest or size mismatch');
      return {type, size, digest};
    } catch (error) {
      await response.body?.cancel().catch(() => {});
      if (file) {
        await file.close();
        await rm(destination, {force: true});
      }
      throw error;
    }
  }
  return null;
}

function rangeOf(header, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2]) || !size) return null;
  const start = match[1] ? Number(match[1]) : Math.max(size - Number(match[2]), 0);
  const end = match[1] && match[2] ? Math.min(Number(match[2]), size - 1) : size - 1;
  return start >= 0 && start < size && end >= start ? {start, end} : null;
}

function errorResponse(res, status, code, message) {
  if (res.headersSent || res.destroyed) { res.destroy(); return; }
  const body = JSON.stringify({errors: [{code, message}]});
  res.writeHead(status, {'content-type': 'application/json', 'content-length': Buffer.byteLength(body)});
  res.end(body);
}

async function startProxy({host = '127.0.0.1', port = 0, resolve, packageTransport, buildCache}) {
  if (typeof resolve !== 'function') throw new Error('An artifact resolver is required');
  // A resolver may return a restored file. Check digest-addressed OCI content before serving even a range.
  const verified = new Map();
  const server = http.createServer(async (req, res) => {
    try {
      if (buildCache && req.url.startsWith('/gradle-build-cache/') && await buildCache.handle(req,res)) return;
      if (!['GET', 'HEAD'].includes(req.method)) {
        res.setHeader('allow', 'GET, HEAD');
        return errorResponse(res, 405, 'UNSUPPORTED', 'Read-only artifact proxy');
      }
      const key = canonicalKey(req.url);
      if (key.startsWith('/v2/')) res.setHeader('docker-distribution-api-version', 'registry/2.0');
      if (key === '/v2/') {
        res.writeHead(200, {'content-type': 'application/json', 'content-length': 2});
        res.end('{}');
        return;
      }
      let route;
      try { route = parseKey(key); } catch { return errorResponse(res, 400, 'NAME_INVALID', 'Invalid artifact path'); }
      if (packageTransport && await packageTransport(req, res, key)) return;
      const item = await resolve(key);
      if (!item) return errorResponse(res, 404, route.manifest ? 'MANIFEST_UNKNOWN' : 'BLOB_UNKNOWN', 'Artifact not found');
      if (!isAbsolute(item.path) || !digestPattern.test(item.digest) || !Number.isSafeInteger(item.size) || item.size < 0 ||
          (route.digest && item.digest !== route.digest) || (route.manifest && !manifestTypes.includes(item.type))) {
        throw new Error('Invalid artifact metadata');
      }
      const file = await open(item.path, 'r');
      try {
        const stat = await file.stat({bigint: true});
        if (!stat.isFile() || stat.size !== BigInt(item.size)) throw new Error('Invalid stored artifact size');
        // npm expects absolute dist.tarball URLs. The recorder stores portable paths so that a PR can
        // replay the same catalog on another ephemeral port. ETags/ranges below cover the response bytes.
        let payload, size = item.size, digest = item.digest;
        if (route.npmMetadata) {
          if (size > 32 * 1024 * 1024) throw new Error('Invalid npm index size');
          const metadata = JSON.parse(await readFile(file, 'utf8'));
          // The offline sandbox has a separate loopback port and forwards that authority through its Unix socket.
          const authority = /^(?:127\.0\.0\.1|localhost|\[::1\]):([1-9]\d{0,4})$/.exec(req.headers.host);
          const destination = authority && Number(authority[1]) <= 65535 ? req.headers.host :
            `${host.includes(':') ? `[${host}]` : host}:${server.address().port}`;
          for (const version of Object.values(metadata.versions || {})) {
            if (version.dist?.tarball) {
              const tarball = version.dist.tarball;
              if (!tarball.startsWith('/npm/')) throw new Error('Invalid stored npm package link');
              validateKey(tarball);
              version.dist.tarball = `http://${destination}${tarball}`;
            }
          }
          payload = Buffer.from(JSON.stringify(metadata));
          size = payload.length;
          digest = createHash('sha256').update(payload).digest('hex');
        }
        if (route.digest) {
          const identity = `${stat.dev}:${stat.ino}:${stat.size}:${stat.ctimeNs}:${route.digest}`;
          if (verified.get(item.path) !== identity) {
            const hash = createHash('sha256');
            for await (const chunk of file.createReadStream({start: 0, autoClose: false})) hash.update(chunk);
            if (hash.digest('hex') !== route.digest) throw new Error('Stored OCI bytes disagree with their requested digest');
            verified.set(item.path, identity);
          }
        }
        const etag = `"${digest}"`;
        res.setHeader('etag', etag);
        res.setHeader('accept-ranges', 'bytes');
        res.setHeader('content-type', item.type);
        if (route.oci) res.setHeader('docker-content-digest', `sha256:${item.digest}`);
        if (req.headers['if-none-match']?.split(',').some(value => ['*', etag, 'W/' + etag].includes(value.trim()))) {
          res.writeHead(304);
          res.end();
          return;
        }
        let range;
        if (req.headers.range && (!req.headers['if-range'] || req.headers['if-range'] === etag)) {
          range = rangeOf(req.headers.range, size);
          if (!range) {
            res.setHeader('content-range', `bytes */${size}`);
            return errorResponse(res, 416, 'RANGE_INVALID', 'Invalid byte range');
          }
          res.setHeader('content-range', `bytes ${range.start}-${range.end}/${size}`);
        }
        res.setHeader('content-length', range ? range.end - range.start + 1 : size);
        res.writeHead(range ? 206 : 200);
        if (req.method === 'HEAD' || !size) { res.end(); return; }
        if (payload) { res.end(range ? payload.subarray(range.start, range.end + 1) : payload); return; }
        await pipeline(file.createReadStream({start: 0, ...range, autoClose: false}), res);
      } finally {
        await file.close();
      }
    } catch (error) {
      const missing = error.code === 'CACHE_MISS';
      errorResponse(res, missing ? 503 : 502, 'UNAVAILABLE', missing ? `Missing published dependency ${canonicalKey(req.url)}` : 'Artifact unavailable');
    }
  });
  await new Promise((accept, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => { server.removeListener('error', reject); accept(); });
  });
  const actualHost = host.includes(':') ? `[${host}]` : host;
  return {url: `http://${actualHost}:${server.address().port}`, close: () => new Promise((accept, reject) => {
    server.close(error => error ? reject(error) : accept());
    server.closeIdleConnections();
  })};
}

module.exports = {startProxy, fetchPublic, validateKey};
