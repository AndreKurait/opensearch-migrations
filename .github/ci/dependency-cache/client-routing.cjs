'use strict';

const { execFile } = require('node:child_process');
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');
const { promisify } = require('node:util');
const { validateKey } = require('./proxy.cjs');
const { brokerProperties } = require('./gradle-wrapper.cjs');

const run = promisify(execFile);
const origins = {
  'docker.io': 'dockerhub', 'registry-1.docker.io': 'dockerhub', 'index.docker.io': 'dockerhub',
  'ghcr.io': 'ghcr', 'quay.io': 'quay', 'public.ecr.aws': 'ecrpublic', 'registry.k8s.io': 'k8s', 'mirror.gcr.io': 'gcr',
  'gcr.io': 'distroless',
  'reg.kyverno.io': 'ghcr', 'cr.fluentbit.io': 'dockerhub',
};
const originEntries = Object.entries(origins).filter(([host]) => !['registry-1.docker.io', 'index.docker.io'].includes(host));
const loopback = name => ['localhost', '127.0.0.1', '[::1]'].includes(name);
function cacheURL(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !loopback(url.hostname) || url.username || url.password ||
      url.search || url.hash || url.pathname !== '/') throw new Error('Expected a loopback dependency cache URL');
  return url;
}

function imageReference(repository, tag) {
  if (!repository || !/^[A-Za-z0-9_@:./-]+$/.test(repository) || (tag && !/^[\w:.-]+$/.test(tag))) return null;
  const parts = repository.split('/');
  const explicit = parts.length > 1 && (parts[0].includes('.') || parts[0].includes(':') || parts[0] === 'localhost');
  const registry = explicit ? parts.shift() : 'docker.io';
  if (!Object.hasOwn(origins, registry)) return null;
  let name = parts.join('/');
  const digestPos = name.lastIndexOf('@');
  const tagPos = name.lastIndexOf(':');
  if (digestPos !== -1) {
    tag ||= name.slice(digestPos + 1);
    name = name.slice(0, digestPos);
    if (name.lastIndexOf(':') > name.lastIndexOf('/')) name = name.slice(0, name.lastIndexOf(':'));
  } else if (tagPos > name.lastIndexOf('/')) {
    tag ||= name.slice(tagPos + 1);
    name = name.slice(0, tagPos);
  }
  tag ||= 'latest';
  if (origins[registry] === 'dockerhub' && !name.includes('/')) name = `library/${name}`;
  const prefix = `${origins[registry]}/${name}`;
  validateKey(`/v2/${prefix}/manifests/${tag}`);
  return { prefix, tag, repository: `${registry}/${name}` };
}

function buildkitConfig(host, port) {
  return originEntries.map(([origin, id]) => `[registry."${origin}"]\n  mirrors = ["${host}:${port}/${id}"]\n`).join('\n') +
    `\n[registry."${host}:${port}"]\n  http = true\n`;
}

// K3s uses a separate embedded containerd. A Docker daemon mirror cannot affect workload pulls.
function k3sConfig(host, port) {
  const mirrors = Object.fromEntries(originEntries.map(([origin, id]) => [origin, {
    endpoint: [`http://${host}:${port}`], rewrite: {'^(.*)$': `${id}/$1`},
  }]));
  // JSON is valid YAML and quotes the regexp and its substitution exactly.
  return JSON.stringify({mirrors}, null, 2);
}

function containersConfig(host, port) {
  return 'unqualified-search-registries = ["docker.io"]\n\n' + originEntries.map(([origin, id]) =>
    `[[registry]]\nprefix = "${origin}"\nlocation = "${host}:${port}/${id}"\ninsecure = true\n`).join('\n');
}

function listen(server, target, bind) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    const done = () => { server.removeListener('error', reject); resolve(); };
    if (bind === undefined) server.listen(target, done); else server.listen(target, bind, done);
  });
}

function stopServer(server) {
  return new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
}

// BuildKit and kind live on private Docker networks. This listener binds one ephemeral
// host bridge IP and accepts only OCI reads. It cannot forward arbitrary URLs or credentials.
async function bridgeRelay(upstream, address) {
  const server = http.createServer(async (request, response) => {
    let resource;
    try {
      if (!['GET', 'HEAD'].includes(request.method) || !request.url.startsWith('/v2/')) throw new Error();
      const target = new URL(request.url, 'http://localhost');
      if (target.origin !== 'http://localhost' || target.hash ||
          [...target.searchParams.keys()].some(k => k !== 'ns')) throw new Error();
      resource = target.pathname;
      if (resource !== '/v2/') validateKey(resource);
    } catch { response.writeHead(400).end(); return; }
    const headers = {};
    for (const h of ['accept','range','if-range','if-none-match']) if (request.headers[h]) headers[h] = request.headers[h];
    const upstreamRequest = http.request(new URL(resource, upstream), { method: request.method, headers }, async r => {
      try { response.writeHead(r.statusCode, r.headers); await pipeline(r, response); } catch { response.destroy(); }
    });
    upstreamRequest.on('error', () => response.writeHead(502).end());
    request.on('aborted', () => upstreamRequest.destroy());
    upstreamRequest.end();
  });
  await listen(server, 0, address);
  return { port: server.address().port, close: () => stopServer(server) };
}

function daemonCall(socket, method, requestPath, headers = {}) {
  return new Promise((resolve, reject) => {
    const r = http.request({ socketPath: socket, path: requestPath, method, headers }, resolve);
    r.on('error', reject); r.end();
  });
}

// Many clients use the daemon's image-create endpoint (Docker CLI, Compose, Testcontainers).
// This socket is for disposable native Docker jobs which already control their own daemon. Other
// Engine APIs remain native, including streams upgraded for exec/buildx. It is not a sandbox.
async function engineAdapter(socket, listener, endpoint, k3sRegistryFile, strict = false) {
  const server = http.createServer(async (req, res) => {
    const requestURL = new URL(req.url, 'http://localhost');
    const create = /^((?:\/v[\d.]+)?)\/images\/create$/.exec(requestURL.pathname);
    try {
      const image = requestURL.searchParams.get('fromImage');
      const pulling = req.method === 'POST' && create;
      const ref = pulling && imageReference(image, requestURL.searchParams.get('tag'));
      if (strict && pulling && !ref) {
        const source = requestURL.searchParams.get('fromSrc');
        const localImage = image && /^(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?\//.test(image);
        if ((image && !localImage) || (source && source !== '-')) {
          res.writeHead(403, { 'content-type': 'application/json' });
          res.end(JSON.stringify({message: 'External image source has no dependency cache route'}));
          return;
        }
      }
      if (!ref) {
        let targetPath = req.url;
        let body;
        const inspect = /^((?:\/v[\d.]+)?\/images\/)(.+?)(\/json|\/history)?$/.exec(requestURL.pathname);
        if (inspect && !['create', 'json', 'prune', 'load'].includes(inspect[2])) {
          const alias = digestAlias(decodeURIComponent(inspect[2]));
          if (alias) targetPath = inspect[1] + encodeURIComponent(alias) + (inspect[3] || '') + requestURL.search;
        }
        if (req.method === 'POST' && /\/containers\/create$/.test(requestURL.pathname) && (aliases.size || k3sRegistryFile)) {
          let collected = '';
          for await (const chunk of req) {
            collected += chunk;
            if (Buffer.byteLength(collected) > 4 * 1024 * 1024) throw new Error('Container configuration too large');
          }
          const config = JSON.parse(collected);
          if (k3sRegistryFile && typeof config.Image === 'string' && /^(?:(?:docker\.io|index\.docker\.io|registry-1\.docker\.io)\/)?rancher\/k3s(?:[:@]|$)/.test(config.Image) &&
              Array.isArray(config.Cmd) && ['server', 'agent'].includes(config.Cmd[0])) {
            config.HostConfig ||= {};
            const destination = '/etc/rancher/k3s/registries.yaml';
            const preconfigured = (config.HostConfig.Binds || []).some(bind => bind.split(':')[1] === destination) ||
              (config.HostConfig.Mounts || []).some(mount => mount.Target === destination);
            if (!preconfigured) {
              config.HostConfig.Binds = [...(config.HostConfig.Binds || []), `${k3sRegistryFile}:${destination}:ro`];
              if (!config.Cmd.includes('--disable-default-registry-endpoint')) config.Cmd.push('--disable-default-registry-endpoint');
            }
          }
          const alias = digestAlias(config.Image);
          if (alias) config.Image = alias;
          body = Buffer.from(JSON.stringify(config));
        }
        const headers = { ...req.headers };
        if (body) { delete headers['transfer-encoding']; headers['content-length'] = body.length; }
        const upstream = http.request({ socketPath: socket, method: req.method, path: targetPath, headers }, async response => {
          try { res.writeHead(response.statusCode, response.headers); await pipeline(response, res); } catch { res.destroy(); }
        });
        upstream.on('error', () => res.writeHead(502).end());
        if (body) upstream.end(body); else req.pipe(upstream);
        return;
      }
      req.resume();
      const local = `${endpoint}/${ref.prefix}`;
      const query = requestURL.searchParams;
      query.set('fromImage', local); query.set('tag', ref.tag);
      // The registry front has anonymous, cached public objects. Do not forward X-Registry-Auth.
      const pull = await daemonCall(socket, 'POST', `${requestURL.pathname}?${query}`);
      res.writeHead(pull.statusCode, { 'content-type': 'application/json' });
      if (pull.statusCode !== 200) { await pipeline(pull, res); return; }
      let buffered = '';
      let failed = false;
      for await (const part of pull) {
        buffered += part.toString();
        let end;
        while ((end = buffered.indexOf('\n')) !== -1) {
          const line = buffered.slice(0, end + 1); buffered = buffered.slice(end + 1);
          if (line.trim()) failed ||= Boolean(JSON.parse(line).error);
          res.write(line);
        }
      }
      if (buffered.trim()) { failed ||= Boolean(JSON.parse(buffered).error); res.write(buffered + '\n'); }
      if (!failed && !ref.tag.startsWith('sha256:')) {
        const alias = new URLSearchParams({ repo: ref.repository, tag: ref.tag });
        const tagged = await daemonCall(socket, 'POST', `${create[1]}/images/${encodeURIComponent(local + ':' + ref.tag)}/tag?${alias}`);
        tagged.resume();
        if (tagged.statusCode !== 201) throw new Error(`Docker could not tag the mirrored image (${tagged.statusCode})`);
      } else if (!failed) {
        // Docker cannot tag a RepoDigest alias. Reconcile original digest references in subsequent requests (below).
        aliases.set(`${ref.repository}@${ref.tag}`, `${local}@${ref.tag}`);
      }
      res.end();
    } catch (error) {
      if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: error.message, message: error.message }) + '\n');
    }
  });
  const aliases = new Map();
  function digestAlias(image) {
    if (typeof image !== 'string' || !image.includes('@sha256:')) return undefined;
    const ref = imageReference(image);
    return ref && aliases.get(`${ref.repository}@${ref.tag}`);
  }
  const upgrades = new Set();
  server.on('upgrade', (req, client, head) => {
    const upstream = net.createConnection(socket);
    upgrades.add(client); upgrades.add(upstream);
    for (const conn of [client, upstream]) {
      conn.on('close', () => upgrades.delete(conn));
      conn.on('error', () => { upstream.destroy(); client.destroy(); });
    }
    upstream.once('connect', () => {
      upstream.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n` +
        req.rawHeaders.map((h, i) => i % 2 ? `${h}\r\n` : `${h}: `).join('') + '\r\n');
      if (head.length) upstream.write(head);
      client.pipe(upstream).pipe(client);
    });
  });
  await listen(server, listener);
  await fs.chmod(listener, 0o600);
  return { close: () => { for (const conn of upgrades) conn.destroy(); return stopServer(server); } };
}

async function wrapperForProxy(repository, temp, upstream) {
  const wrapper = path.join(repository, 'gradle', 'wrapper');
  let properties;
  try { properties = await fs.readFile(path.join(wrapper, 'gradle-wrapper.properties'), 'utf8'); }
  catch (error) { if (error.code === 'ENOENT') return undefined; throw error; }
  const updated = brokerProperties(properties, upstream.origin);
  await fs.mkdir(path.join(temp, 'gradle', 'wrapper'), { recursive: true });
  const result = path.join(temp, 'gradle', 'wrapper', 'gradle-wrapper.jar');
  await fs.copyFile(path.join(wrapper, 'gradle-wrapper.jar'), result);
  await fs.writeFile(result.replace(/\.jar$/, '.properties'), updated);
  return result;
}

async function configureSkopeo(temp, config, env) {
  let executable;
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    const candidate = path.join(dir, 'skopeo');
    try { await fs.access(candidate, fs.constants.X_OK); executable = path.resolve(candidate); break; }
    catch (error) { if (!['ENOENT', 'EACCES', 'ENOTDIR'].includes(error.code)) throw error; }
  }
  if (!executable) return;
  // Skopeo accepts an explicit registries.conf option; it does not read the
  // CONTAINERS_REGISTRIES_CONF environment variable on its own.
  const bin = path.join(temp, 'bin');
  await fs.mkdir(bin, { mode: 0o700 });
  const wrap = path.join(bin, 'skopeo');
  const quote = text => "'" + text.replaceAll("'", "'\\''") + "'";
  await fs.writeFile(wrap, `#!/bin/sh\nexec ${quote(executable)} --registries-conf ${quote(config)} "$@"\n`, { mode: 0o700 });
  env.PATH = `${bin}${path.delimiter}${process.env.PATH}`;
  env.SKOPEO_BIN = wrap;
}

async function setupClients({ url, mode, repository = process.cwd() }) {
  const upstream = cacheURL(url);
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'dep-routing-'));
  const cleanup = [];
  const close = async () => {
    try { await Promise.allSettled(cleanup.splice(0).reverse().map(fn => fn())); }
    finally { await fs.rm(temp, { recursive: true, force: true }); }
  };
  try {
    const registries = path.join(temp, 'registries.conf');
    await fs.writeFile(registries, containersConfig(upstream.hostname, upstream.port), { mode: 0o600 });
    const env = {
      MAVEN_REPOSITORY_URL: `${upstream.origin}/maven/`, CONTAINERS_REGISTRIES_CONF: registries,
      npm_config_registry: `${upstream.origin}/npm/`, npm_config_audit: 'false', npm_config_update_notifier: 'false',
      PIP_INDEX_URL: `${upstream.origin}/pypi/simple/`, PIP_EXTRA_INDEX_URL: '',
      PIPENV_PYPI_MIRROR: `${upstream.origin}/pypi/simple/`, PIP_DISABLE_PIP_VERSION_CHECK: '1',
      CI_DEPENDENCY_CACHE_URL: upstream.origin, CI_DEPENDENCY_PROXY_URL: upstream.origin,
    };
    await configureSkopeo(temp, registries, env);
    const wrapper = await wrapperForProxy(repository, temp, upstream);
    if (wrapper) env.CI_DEPENDENCY_GRADLE_WRAPPER_JAR = wrapper;
    const hostedReader = mode === 'read' && process.platform === 'linux' && process.env.GITHUB_ACTIONS === 'true' &&
      process.env.RUNNER_ENVIRONMENT === 'github-hosted';
    if ((mode === 'record' || hostedReader) && process.env.CI_CACHE_TRUSTED_DOCKER === '1') {
      if (!(process.env.CODEBUILD_BUILD_ID || process.env.GITHUB_ACTIONS === 'true')) throw new Error('Docker routing requires an opted-in disposable CI job');
      const original = process.env.DOCKER_HOST || 'unix:///var/run/docker.sock';
      if (!original.startsWith('unix:///')) throw new Error('Trusted Docker routing requires a local Unix socket');
      const inspection = await run('docker', ['network', 'inspect', 'bridge', '--format', '{{(index .IPAM.Config 0).Gateway}}']);
      const bridge = inspection.stdout.trim();
      if (!net.isIPv4(bridge) || !(bridge.startsWith('10.') || bridge.startsWith('192.168.') || /^172\.(1[6-9]|2\d|3[01])\./.test(bridge))) {
        throw new Error('Docker bridge must use a private IPv4 gateway');
      }
      const relay = await bridgeRelay(upstream, bridge); cleanup.push(relay.close);
      const buildkit = path.join(temp, 'buildkit.toml');
      await fs.writeFile(buildkit, buildkitConfig(bridge, relay.port));
      env.BUILDKIT_EXTRA_CONFIG = buildkit;
      env.CI_DEPENDENCY_REGISTRY_ENDPOINT = `${bridge}:${relay.port}`;
      const listener = path.join(temp, 'docker.sock');
      const k3s = path.join(temp, 'k3s-registries.yaml');
      await fs.writeFile(k3s, k3sConfig(bridge, relay.port), {mode:0o644});
      const adapter = await engineAdapter(original.slice('unix://'.length), listener, upstream.host, k3s, process.env.CI_DEPENDENCY_STRICT_READ === '1' || process.env.CI_DEPENDENCY_MODE === 'replay');
      cleanup.push(adapter.close);
      env.DOCKER_HOST = `unix://${listener}`;
    }
    return { env, close };
  } catch (error) { await close(); throw error; }
}
module.exports = { setupClients, buildkitConfig, k3sConfig, containersConfig, imageReference, bridgeRelay, engineAdapter };
