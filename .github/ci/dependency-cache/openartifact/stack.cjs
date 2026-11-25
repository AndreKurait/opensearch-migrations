'use strict';

const { spawn } = require('node:child_process');
const { randomBytes, randomUUID } = require('node:crypto');
const fs = require('node:fs/promises');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { setTimeout: sleep } = require('node:timers/promises');
const { repositories } = require('./repos.cjs');

const images = require('./images.json');
const session = () => randomBytes(32).toString('hex');
const ref = /^[a-f0-9]{64}$/;
const approved = new Set(repositories.filter((r) => r.repo_type === 'proxy').map((r) => r.name));

async function docker(args, input) {
  return new Promise((resolve, reject) => {
    const p = spawn('docker', args, { stdio: ['pipe', 'pipe', 'pipe'] });
    const out = [],
      err = [];
    p.stdout.on('data', (bytes) => out.push(bytes));
    p.stderr.on('data', (bytes) => err.push(bytes));
    p.once('error', reject);
    p.once('close', (code) =>
      code === 0
        ? resolve(Buffer.concat(out).toString().trim())
        : reject(new Error(`docker ${args[0]} failed (${code}): ${Buffer.concat(err).toString().slice(-1500)}`)),
    );
    p.stdin.on('error', () => {});
    p.stdin.end(input);
  });
}

async function ready(check, description) {
  for (let i = 0; i < 120; i++) {
    if (await check().catch(() => false)) return;
    await sleep(500);
  }
  throw new Error(`${description} readiness timed out`);
}

function validateRows(snapshot) {
  if (!Array.isArray(snapshot) || snapshot.length > 100000) throw new Error('Invalid OpenArtifact snapshot');
  const unique = new Set();
  for (const row of snapshot) {
    if (
      !row ||
      typeof row !== 'object' ||
      Object.keys(row).some((k) => !['repo', 'key', 'digest', 'type', 'size', 'status', 'accessed'].includes(k)) ||
      !approved.has(row.repo) ||
      typeof row.key !== 'string' ||
      !row.key ||
      row.key.length > 4096 ||
      /[?\x00-\x1f\\#]/.test(row.key) ||
      row.key.split('/').some((part) => part === '..' || part === '.') ||
      typeof row.type !== 'string' ||
      row.type.length > 255 ||
      /[\x00-\x1f]/.test(row.type) ||
      !Number.isSafeInteger(row.size) ||
      row.size < 0 ||
      !Number.isSafeInteger(row.accessed) ||
      row.accessed < 0 ||
      !((row.status === 200 && ref.test(row.digest)) || (row.status === 404 && row.digest === null && row.size === 0))
    )
      throw new Error('Invalid OpenArtifact proxy cache row');
    const id = JSON.stringify([row.repo, row.key]);
    if (unique.has(id)) throw new Error('Duplicate OpenArtifact proxy cache key');
    unique.add(id);
  }
  return snapshot;
}

async function startStack({ mode = 'record', storage = { type: 'filesystem' }, snapshot = [] } = {}) {
  if (!['read', 'record'].includes(mode) || !['filesystem', 's3'].includes(storage.type))
    throw new Error('Invalid OpenArtifact worker configuration');
  validateRows(snapshot);
  const bootstrap = process.env.CI_BOOTSTRAP_IMAGE_IDS;
  const localImages = bootstrap ? JSON.parse(bootstrap) : Object.fromEntries(Object.entries(images).map(([name, image]) => [name, image.pin]));
  if (bootstrap && !Object.keys(images).every(name => /^sha256:[a-f0-9]{64}$/.test(localImages[name])))
    throw new Error('Invalid local bootstrap image IDs');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'openartifact-stack-'));
  const project = `oa-ci-${randomUUID().slice(0, 12)}`;
  const pg = project + '-pg',
    app = project + '-app',
    volume = project + '-blobs';
  const password = session(),
    adminPassword = session();
  const cleanup = [() => fs.rm(dir, { recursive: true, force: true })];
  const close = async () => {
    const results = [];
    for (const hook of cleanup.splice(0).reverse()) {
      try {
        await hook();
      } catch (error) {
        results.push(error);
      }
    }
    if (results.length) throw new AggregateError(results, 'Could not remove the OpenArtifact worker stack');
  };
  const psql = (sql) =>
    docker(
      ['exec', '-i', pg, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'openartifact'],
      sql,
    );
  const envfile = async (file, env) => {
    for (const v of Object.values(env)) if (/[\r\n]/.test(v)) throw new Error('Invalid environment value');
    await fs.writeFile(
      path.join(dir, file),
      Object.entries(env)
        .map(([k, v]) => `${k}=${v}\n`)
        .join(''),
      { mode: 0o600 },
    );
    return path.join(dir, file);
  };
  try {
    await docker(['network', 'create', ...(mode === 'read' ? ['--internal'] : []), project]);
    cleanup.push(() => docker(['network', 'rm', project]));
    const network = JSON.parse(await docker(['network', 'inspect', project]))[0];
    const gateway = network.IPAM.Config[0].Gateway;
    const pgEnv = await envfile('pg.env', { POSTGRES_DB: 'openartifact', POSTGRES_PASSWORD: password });
    await docker([
      'run',
      ...(bootstrap ? ['--pull=never'] : []),
      '-d',
      '--name',
      pg,
      '--network',
      project,
      '--network-alias',
      'db',
      '--security-opt',
      'no-new-privileges',
      '--env-file',
      pgEnv,
      localImages.postgres,
    ]);
    cleanup.push(() => docker(['rm', '-fv', pg]));
    await ready(async () => (await psql('SELECT 1;')) === '1', 'PostgreSQL');
    await docker(['volume', 'create', volume]);
    cleanup.push(() => docker(['volume', 'rm', volume]));
    const config = {
      SERVER_HOST: '0.0.0.0',
      SERVER_PORT: '8080',
      DB_DRIVER: 'postgres',
      DB_HOST: 'db',
      DB_PORT: '5432',
      DB_USER: 'postgres',
      DB_NAME: 'openartifact',
      DB_PASSWORD: password,
      DB_SSLMODE: 'disable',
      STORAGE_BACKEND: storage.type,
      STORAGE_PATH: '/var/lib/artifacts',
      AUTH_HMAC_KEY: session(),
      OPENARTIFACT_SECRET_KEY: session(),
      OPENARTIFACT_BOOTSTRAP_ADMIN_PASSWORD: adminPassword,
      OPENARTIFACT_ROLE: 'web',
      OPENARTIFACT_SCANNER_OFFLINE: 'true',
      OPENARTIFACT_RECOVERY_JOURNAL_ENABLED: 'false',
      STORAGE_PRESIGN_DOWNLOADS: 'false',
    };
    if (storage.type === 's3') {
      const endpoint = new URL(storage.url);
      if (
        endpoint.protocol !== 'http:' ||
        endpoint.hostname !== '127.0.0.1' ||
        !endpoint.port ||
        endpoint.pathname !== '/' ||
        endpoint.search ||
        endpoint.hash ||
        endpoint.username ||
        !/^[a-z0-9-]{3,63}$/.test(storage.bucket)
      ) {
        throw new Error('OpenArtifact S3 adapter must use the job-local loopback endpoint');
      }
      const relay = net.createServer((client) => {
        const local = net.createConnection({ host: '127.0.0.1', port: Number(endpoint.port) });
        local.on('error', () => client.destroy());
        client.on('error', () => local.destroy());
        client.once('close', () => local.destroy());
        local.once('close', () => client.destroy());
        client.pipe(local).pipe(client);
      });
      await new Promise((ok, fail) => {
        relay.once('error', fail);
        relay.listen(0, gateway, ok);
      });
      cleanup.push(() => new Promise((resolve) => relay.close(resolve)));
      if (!storage.credentials?.accessKeyId || !storage.credentials.secretAccessKey)
        throw new Error('Missing ephemeral OpenArtifact adapter credentials');
      Object.assign(config, {
        STORAGE_BUCKET: storage.bucket,
        STORAGE_REGION: storage.region || 'us-east-1',
        STORAGE_ENDPOINT: `http://${gateway}:${relay.address().port}`,
        STORAGE_ACCESS_KEY: storage.credentials.accessKeyId,
        STORAGE_SECRET_KEY: storage.credentials.secretAccessKey,
        STORAGE_S3_FORCE_PATH_STYLE: 'true',
      });
    }
    const appEnv = await envfile('app.env', config);
    await docker([
      'run',
      ...(bootstrap ? ['--pull=never'] : []),
      '-d',
      '--name',
      app,
      '--network',
      project,
      '--security-opt',
      'no-new-privileges',
      '--cap-drop',
      'ALL',
      ...(mode === 'read' ? [] : ['-p', '127.0.0.1::8080']),
      '--env-file',
      appEnv,
      '-v',
      `${volume}:/var/lib/artifacts`,
      localImages.openartifact,
    ]);
    cleanup.push(() => docker(['rm', '-fv', app]));
    let authority;
    if (mode === 'read') {
      // Docker omits published ports on --internal networks. A host loopback relay reaches this one IP without giving the web container a default route.
      const addr = JSON.parse(await docker(['inspect', app]))[0].NetworkSettings.Networks[project].IPAddress;
      if (net.isIP(addr) !== 4) throw new Error('Isolated OpenArtifact has no IPv4 address');
      const peers = new Set();
      const web = net.createServer((client) => {
        const service = net.createConnection({ host: addr, port: 8080 });
        peers.add(client);
        client.on('error', () => service.destroy());
        service.on('error', () => client.destroy());
        client.once('close', () => {
          peers.delete(client);
          service.destroy();
        });
        service.once('close', () => client.destroy());
        client.pipe(service).pipe(client);
      });
      await new Promise((ok, fail) => {
        web.once('error', fail);
        web.listen(0, '127.0.0.1', ok);
      });
      cleanup.push(
        () =>
          new Promise((resolve) => {
            for (const peer of peers) peer.destroy();
            web.close(resolve);
          }),
      );
      authority = `127.0.0.1:${web.address().port}`;
    } else authority = await docker(['port', app, '8080/tcp']);
    if (!/^127\.0\.0\.1:\d+$/.test(authority)) throw new Error('Unexpected OpenArtifact bind');
    const url = 'http://' + authority;
    await ready(async () => (await fetch(url + '/ready')).ok, 'OpenArtifact');
    if (mode === 'record') {
      const json = { 'content-type': 'application/json' };
      const auth = await fetch(url + '/api/v1/auth/login', {
        method: 'POST',
        headers: json,
        body: JSON.stringify({ username: 'admin', password: adminPassword }),
      });
      if (!auth.ok) throw new Error(`OpenArtifact bootstrap login returned ${auth.status}`);
      const cookies = auth.headers.getSetCookie().map((c) => c.split(';', 1)[0]);
      const csrf = cookies.find((c) => c.startsWith('oa_csrf='))?.slice('oa_csrf='.length);
      if (!csrf) throw new Error('Missing bootstrap CSRF token');
      for (const repo of repositories) {
        const response = await fetch(url + '/api/v1/repositories', {
          method: 'POST',
          headers: { ...json, cookie: cookies.join('; '), 'x-csrf-token': csrf },
          body: JSON.stringify(repo),
        });
        if (!response.ok)
          throw new Error(
            `OpenArtifact create repository ${repo.name} returned ${response.status}: ${(await response.text()).slice(0, 250)}`,
          );
        await response.body?.cancel();
      }
    } else {
      // The repository creation API resolves every origin hostname. An isolated playback worker restores the same reviewed definitions offline.
      const local = Buffer.from(JSON.stringify(repositories)).toString('base64');
      await psql(`BEGIN;
        CREATE TEMP TABLE oa_definitions AS SELECT * FROM jsonb_to_recordset(convert_from(decode('${local}','base64'),'UTF8')::jsonb)
          AS d(name text, format text, repo_type text, upstream_url text, cache_ttl_seconds int, negative_cache_ttl_seconds int, member_repos jsonb);
        INSERT INTO repositories(name,format,repo_type,description,public,upstream_url,cache_ttl_seconds,negative_cache_ttl_seconds,presign_downloads,project_id)
          SELECT name,format,repo_type,'',true,upstream_url,coalesce(cache_ttl_seconds,86400),coalesce(negative_cache_ttl_seconds,300),false,
            (SELECT id FROM projects WHERE name='system') FROM oa_definitions;
        INSERT INTO repository_group_members(group_repo_id,member_repo_id,priority)
          SELECT g.id,r.id,m.ordinality-1 FROM oa_definitions d JOIN repositories g ON d.name=g.name
          CROSS JOIN LATERAL jsonb_array_elements_text(d.member_repos) WITH ORDINALITY AS m(name,ordinality)
          JOIN repositories r ON r.name=m.name;
        COMMIT;`);
    }
    // Restore a data-only projection into a new schema, mapping by fixed public repository names. No SQL or credentials enter the snapshot.
    if (snapshot.length) {
      const encoded = Buffer.from(JSON.stringify(snapshot)).toString('base64');
      await psql(`INSERT INTO proxy_cache_entries(repository_id,cache_key,blob_key,content_type,size_bytes,status_code,last_hit_at,expires_at)
        SELECT r.id, e.key, e.digest, e.type, e.size, e.status, to_timestamp(e.accessed/1000.0),
          ${mode === 'read' ? "now()+interval '30 days'" : 'to_timestamp(e.accessed/1000.0) + make_interval(secs => CASE WHEN e.status=404 THEN r.negative_cache_ttl_seconds ELSE r.cache_ttl_seconds END)'}
        FROM jsonb_to_recordset(convert_from(decode('${encoded}','base64'),'UTF8')::jsonb)
          AS e(repo text,key text,digest text,type text,size bigint,status int,accessed bigint) JOIN repositories r ON r.name=e.repo;`);
    }
    async function exportSnapshot() {
      const data = await psql(`SELECT COALESCE(json_agg(a ORDER BY a.repo,a.key),'[]') FROM (
        SELECT r.name repo, e.cache_key key, e.blob_key digest, COALESCE(e.content_type,'') type, e.size_bytes size,
        e.status_code status, floor(EXTRACT(EPOCH FROM COALESCE(e.last_hit_at,e.created_at))*1000)::bigint accessed
        FROM proxy_cache_entries e JOIN repositories r ON e.repository_id=r.id WHERE r.public AND r.upstream_username IS NULL
          AND r.upstream_password_enc IS NULL AND r.repo_type='proxy'
      ) a;`);
      return validateRows(JSON.parse(data));
    }
    async function catalog() {
      const byDigest = new Map();
      for (const r of await exportSnapshot())
        if (r.digest) {
          const old = byDigest.get(r.digest);
          if (!old || r.accessed > old.accessed)
            byDigest.set(r.digest, {
              key: r.digest,
              digest: r.digest,
              size: r.size,
              type: r.type,
              accessed: r.accessed,
            });
        }
      return [...byDigest.values()];
    }
    async function materialize(entry, destination) {
      if (!ref.test(entry.digest) || !path.isAbsolute(destination))
        throw new Error('Invalid OpenArtifact blob export');
      if (storage.type === 's3') return storage.materialize(entry, destination);
      await docker([
        'cp',
        `${app}:/var/lib/artifacts/${entry.digest.slice(0, 2)}/${entry.digest.slice(2)}`,
        destination,
      ]);
    }
    return {
      url,
      env: {
        MAVEN_REPOSITORY_URL: url + '/repos/maven-all/',
        npm_config_registry: url + '/repos/npm/',
        PIP_INDEX_URL: url + '/repos/pypi/simple/',
      },
      snapshot: exportSnapshot,
      catalog,
      materialize,
      close,
    };
  } catch (error) {
    await close();
    throw error;
  }
}

module.exports = { startStack, validateRows };
