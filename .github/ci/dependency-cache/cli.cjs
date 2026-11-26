'use strict';

const {spawn, execFile} = require('node:child_process');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {promisify} = require('node:util');
const {setTimeout: delay} = require('node:timers/promises');
const {startProxy} = require('./proxy.cjs');
const {setupClients} = require('./client-routing.cjs');
const {openLocalRecorder, isFreshEntry, readReceipt, MAX_RECEIPT_BYTES} = require('./receipts.cjs');

const project = 'migrations-main-dependency-cache';
const sourceBuild = /^migrations-github-eks:([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/;
const receiptPattern = /^migrations-main-requests-v1-\d+-\d+-[0-9a-f-]{36}$/;
const receiptPath = '.migrations-public-dependency-cache-v1/main-requests.json';
const execute = promisify(execFile);
const cacheEnv = ['ACTIONS_RUNTIME_TOKEN', 'ACTIONS_RESULTS_URL', 'ACTIONS_CACHE_SERVICE_V2', 'ACTIONS_CACHE_MODE'];

async function aws(service, operation, ...flags) {
  try {
    const {stdout} = await execute('aws', ['--region', 'us-west-2', '--no-cli-pager', '--output', 'json', service, operation, ...flags], {maxBuffer: 5 * 1024 * 1024});
    return stdout.trim() ? JSON.parse(stdout) : {};
  } catch (error) {
    // Never relay AWS CLI argv, the private bucket, OIDC identifiers or the cache grant.
    const code = error.stderr?.match(/An error occurred \((\w+)\)/)?.[1] || 'request failed';
    throw new Error(`${service} ${operation}: ${code}`);
  }
}

async function run(command, args, env = process.env) {
  const child = spawn(command, args, {env, stdio: 'inherit'});
  const stop = () => child.kill('SIGTERM');
  process.once('SIGTERM', stop);
  try {
    await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', (code, signal) => code === 0 ? resolve() : reject(new Error(`Worker exited with ${signal || code}`)));
    });
  } finally { process.off('SIGTERM', stop); }
}

async function serve(store, mode, body, packageTransport) {
  let proxy;
  let clients;
  let buildCache, buildkitDir, buildkitCache;
  try {
    if (process.env.GITHUB_ACTIONS === 'true' && process.env.CI_GRADLE_REMOTE_CACHE === 'true') {
      buildCache = await require('./gradle-build-cache.cjs').openBuildCache({
        mode, toolkit: await require('./github-store.cjs').loadToolkit(),
      });
    }
    if (process.env.GITHUB_ACTIONS === 'true' && process.env.CI_CACHE_TRUSTED_DOCKER === '1') {
      buildkitDir = await fs.mkdtemp(path.join(os.tmpdir(), 'buildkit-cache-'));
      buildkitCache = require('./buildkit-cache.cjs').reader({directory:buildkitDir, toolkit:await require('./github-store.cjs').loadToolkit()});
    }
    const catalog = mode === 'read' && store.entries ? new Map(store.entries.map(entry => [entry.key, entry])) : undefined;
    proxy = await startProxy({resolve: key => store.resolve(key), catalog, packageTransport, buildCache, buildkitCache});
    clients = await setupClients({url: proxy.url, mode});
    if (buildCache) {
      clients.env.CI_GRADLE_BUILD_CACHE_URL = `${proxy.url}/gradle-build-cache/`;
      clients.env.CI_GRADLE_BUILD_CACHE_PUSH = String(buildCache.write);
    }
    await body(clients.env);
  } finally {
    try { await clients?.close(); } finally {
      try { await proxy?.close(); } finally {
        try {
          if (buildCache) console.log(`Gradle remote cache ${JSON.stringify(buildCache.metrics)}`);
          await buildCache?.close();
          if (buildkitDir) await fs.rm(buildkitDir, {recursive:true, force:true});
        } finally { await store.close(); }
      }
    }
  }
}

// Use the CodeBuild image's AWS CLI so playback can bootstrap without npm.
async function nativeWorker(command, args) {
  const bucket = process.env.CI_RECOVERY_BUCKET;
  const storage = {
    async *catalog() {
      const result = await aws('s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', 'dependency-cache/v1/catalog/');
      for (const row of result.Contents || []) yield row.Key;
    },
    get: (key, file) => aws('s3api', 'get-object', '--bucket', bucket, '--key', key, file),
  };
  const snapshot = await require('./aws-store.cjs').openPublisher({storage});
  const entries = require('./github-store.cjs').manifestLookup(snapshot.entries);
  const strict = process.env.CI_DEPENDENCY_MODE === 'replay';
  if (process.env.CI_DEPENDENCY_ORIGIN_REPORT) await fs.writeFile(process.env.CI_DEPENDENCY_ORIGIN_REPORT, '', {mode: 0o600});
  const stats = {restored: 0, bytes: 0, originRequests: 0, misses: 0};
  const store = await openLocalRecorder({
    receiptFile: process.env.CI_DEPENDENCY_RECEIPT,
    async fetchArtifact(key, file) {
      const entry = entries.get(key);
      if (entry && (strict || isFreshEntry(entry))) {
        if (entry.missing) return null;
        await snapshot.materialize(entry, file);
        stats.restored++;
        stats.bytes += entry.size;
        return entry;
      }
      if (!entry) stats.misses++;
      if (strict) throw new Error(`Native dependency cache miss: ${key}`);
      stats.originRequests++;
      return require('./proxy.cjs').fetchPublic(key, file);
    },
  });
  try {
    await serve(store, 'record', env => run(command, args, {...process.env, ...env}));
    if (strict && (!stats.restored || stats.misses)) throw new Error('Incomplete native dependency cache replay');
  } finally {
    await snapshot.close();
    if (process.env.CI_DEPENDENCY_CACHE_STATS) await fs.writeFile(process.env.CI_DEPENDENCY_CACHE_STATS, JSON.stringify(stats));
    console.log(`Native dependency cache ${JSON.stringify(stats)}`);
  }
}

async function nativeReceipt(buildId, expectedSha = process.env.CI_CACHE_RECEIPT_SHA || process.env.CI_CACHE_MAIN_SHA) {
  if (!/^[a-f0-9]{40}$/.test(expectedSha)) throw new Error('Invalid native receipt SHA');
  const match = sourceBuild.exec(buildId);
  if (!match) throw new Error('Invalid main worker build ID');
  const {builds} = await aws('codebuild', 'batch-get-builds', '--ids', buildId);
  const build = builds?.[0];
  const env = Object.fromEntries((build?.environment?.environmentVariables || []).map(v => [v.name, v.value]));
  const bucket = process.env.CI_DEPENDENCY_BUCKET;
  const key = `builds/${match[1]}/results.zip`;
  if (!build?.buildComplete || build.id !== buildId || build.projectName !== 'migrations-github-eks' ||
      !/^(refs\/heads\/main|refs\/pull\/[1-9]\d*\/head)$/.test(env.CI_GIT_REF) || env.CI_GIT_SHA !== expectedSha ||
      build.artifacts?.location !== `arn:aws:s3:::${bucket}/${key}`) throw new Error('Build is not the selected, completed main worker');
  const main = env.CI_GIT_REF === 'refs/heads/main';
  if (main) await execute('git', ['merge-base', '--is-ancestor', expectedSha, 'HEAD']);
  const info = await aws('s3api', 'head-object', '--bucket', bucket, '--key', key);
  if (!(info.ContentLength > 0 && info.ContentLength <= 64 * 1024 ** 2)) throw new Error('Unexpected worker archive size');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'main-receipt-'));
  try {
    const zip = path.join(dir, 'results.zip');
    await aws('s3api', 'get-object', '--bucket', bucket, '--key', key, zip);
    const script = 'import sys,zipfile\nwith zipfile.ZipFile(sys.argv[1]) as z:\n entries=[i for i in z.infolist() if i.filename=="dependency-requests.json"]\n if len(entries)!=1 or entries[0].file_size>int(sys.argv[2]): raise ValueError("missing or oversized receipt")\n sys.stdout.buffer.write(z.read(entries[0]))';
    const {stdout} = await execute('python3', ['-c', script, zip, String(MAX_RECEIPT_BYTES)], {maxBuffer: MAX_RECEIPT_BYTES + 1024});
    const observations = readReceipt(stdout);
    // A PR proposes only public URLs. The protected publisher resolves them independently
    // and chooses its own time, digest and response. Candidate bytes never enter the store.
    return main ? observations : observations.map(({key}) => ({key, accessed: Date.now()}));
  } finally { await fs.rm(dir, {recursive: true, force: true}); }
}

function receiptKeys(value) {
  const [first, ...rest] = value.split(',');
  if (!receiptPattern.test(first) || rest.length > 3 || rest.some(id => !/^[a-f0-9-]{36}$/.test(id)))
    throw new Error('Invalid main worker receipt batch');
  const prefix = first.slice(0, -36);
  return [first, ...rest.map(id => prefix + id)];
}

async function githubReceipt(key) {
  if (!receiptPattern.test(key)) throw new Error('Invalid main worker receipt key');
  const cache = await require('./github-store.cjs').loadToolkit();
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'main-github-receipt-'));
  try {
    const destination = path.join(dir, 'requests.json');
    if (await cache.restoreCache([receiptPath], key, [], {destination, maxBytes: 2 * 1024 ** 2}) !== key) throw new Error('Main worker receipt has expired');
    return readReceipt(await fs.readFile(destination));
  } finally { await fs.rm(dir, {force: true, recursive: true}); }
}

async function importMain(observations) {
  const store = await require('./aws-store.cjs').openRecorder();
  try {
    let next = 0;
    const workers = Array.from({length: Math.min(8, observations.length)}, async () => {
      while (next < observations.length) await store.importObservation(observations[next++]);
    });
    const results = await Promise.allSettled(workers);
    for (const result of results) if (result.status === 'rejected') throw result.reason;
  } finally { await store.close(); }
}

async function cloudWorker() {
  const mode = process.env.CI_CACHE_MODE;
  if (!['record', 'publish', 'both'].includes(mode)) throw new Error('Invalid worker mode');
  if (mode === 'record' && process.env.CI_CACHE_BUILD_ID) {
    const observations = await nativeReceipt(process.env.CI_CACHE_BUILD_ID);
    await importMain(observations);
    console.log(`Imported ${observations.length} verified native dependency requests.`);
    return;
  }
  // Linux repository tests install this verified sandbox prerequisite before requesting test dependencies.
  await importMain([{key: '/raw/bubblewrap/bubblewrap_0.9.0-1ubuntu0.3_amd64.deb',
    digest: '2461f1beee9cb04c8942739fe1a2b37e7b7c2a3d518f0779dc75f9245baa3094', accessed: Date.now()}]);
  if (mode !== 'publish') {
    const recorder = await require('./aws-store.cjs').openRecorder();
    await serve(recorder, 'record', async env => {
      const proxy = new URL(env.CI_DEPENDENCY_PROXY_URL);
      const versions = await fs.readFile('gradle/libs.versions.toml', 'utf8');
      const lucene = /^lucene10 = "([0-9]+\.[0-9]+\.[0-9]+)"$/m.exec(versions)?.[1];
      if (!lucene) throw new Error('Missing repository Lucene 10 version');
      for (const extension of ['pom', 'jar', 'module']) {
        const coordinate = `/maven/org/apache/lucene/lucene-core/${lucene}/lucene-core-${lucene}.${extension}`;
        const result = await recorder.resolve(coordinate);
        if (!result && extension !== 'module') throw new Error(`Missing required Maven artifact ${coordinate}`);
      }
      const images = [
        'dockerhub/kindest/node@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5',
        'dockerhub/library/sonarqube@sha256:ef0290a2deabad86a2623552712033a7107a9f5b310af00519b4ddd5b348ef4e',
      ];
      for (const image of images) {
        const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'ci-image-seed-'));
        try {
          await run('skopeo', ['--override-os', 'linux', '--override-arch', 'amd64', 'copy', '--src-tls-verify=false',
            `docker://${proxy.host}/${image}`, `dir:${temp}`], {...process.env, ...env});
        } finally { await fs.rm(temp, {recursive: true, force: true}); }
      }
      const npmCache = await fs.mkdtemp(path.join(os.tmpdir(), 'orchestration-npm-cache-'));
      try {
        await run('npm', ['ci', '--prefix', path.resolve('orchestrationSpecs')],
          {...process.env, ...env, npm_config_cache: npmCache});
      } finally { await fs.rm(npmCache, {recursive: true, force: true}); }
      const pipenv = await fs.mkdtemp(path.join(os.tmpdir(), 'cluster-tools-pipenv-'));
      try {
        const bin = path.join(pipenv, 'bin');
        const pythonEnv = {...process.env, ...env, PATH: `${bin}:${env.PATH || process.env.PATH}`,
          PIP_NO_CACHE_DIR: '1', PIPENV_CACHE_DIR: path.join(pipenv, 'cache'), WORKON_HOME: path.join(pipenv, 'venvs'),
          PIPENV_PIPFILE: path.resolve('migrationConsole/cluster_tools/Pipfile'), PIPENV_IGNORE_VIRTUALENVS: '1'};
        await run('python3.11', ['-m', 'venv', pipenv], pythonEnv);
        await run(path.join(bin, 'python'), ['-m', 'pip', 'install', 'pipenv==2026.5.0'], pythonEnv);
        await run(path.join(bin, 'pipenv'), ['install', '--deploy', '--dev'], pythonEnv);
      } finally { await fs.rm(pipenv, {recursive: true, force: true}); }
      await run(process.execPath, [path.join(__dirname, 'smoke.cjs')], {...process.env, ...env});
    });
  }
  if (mode === 'record') return;
  const parameter = process.env.CI_CACHE_GRANT_PARAMETER;
  if (!/^\/migrations\/cache-grants\/\d+-\d+-[a-f0-9-]{36}$/.test(parameter || '')) throw new Error('Missing scoped cache publication grant');
  const grant = JSON.parse((await aws('ssm', 'get-parameter', '--name', parameter, '--with-decryption')).Parameter.Value);
  await aws('ssm', 'delete-parameter', '--name', parameter);
  for (const key of cacheEnv) if (typeof grant[key] === 'string') process.env[key] = grant[key];
  process.env.GITHUB_WORKSPACE = process.cwd();
  if (process.env.CI_CACHE_RECEIPT_KEY) {
    const receipts = [];
    for (const key of receiptKeys(process.env.CI_CACHE_RECEIPT_KEY)) receipts.push(...await githubReceipt(key));
    await importMain(receipts);
  }
  if (process.env.CI_CACHE_BUILD_ID) await importMain(await nativeReceipt(process.env.CI_CACHE_BUILD_ID));
  const catalog = await require('./aws-store.cjs').openPublisher();
  try {
    const result = await require('./github-store.cjs').publish({entries: catalog.entries, materialize: catalog.materialize, generation: grant.generation});
    console.log(`Published ${result.entries} paths across ${result.blobs} entries (${result.bytes} bytes).`);
  } finally { await catalog.close(); }
}

async function launchCloud() {
  const env = process.env;
  if (env.GITHUB_REF !== 'refs/heads/main' || env.GITHUB_REPOSITORY !== 'AndreKurait/opensearch-migrations' ||
      !/^\d+$/.test(env.GITHUB_RUN_ID) || !/^\d+$/.test(env.GITHUB_RUN_ATTEMPT) || !/^[0-9a-f]{40}$/.test(env.CI_CACHE_MAIN_SHA || '')) throw new Error('Publication must run from the protected main workflow');
  const key = env.CI_CACHE_RECEIPT_KEY || '';
  const native = env.CI_CACHE_BUILD_ID || '';
  if (key) receiptKeys(key);
  if ((native && !sourceBuild.test(native)) || (key && native)) throw new Error('Invalid receipt input');
  if (!env.ACTIONS_RUNTIME_TOKEN || !env.ACTIONS_RESULTS_URL || !env.ACTIONS_CACHE_SERVICE_V2) throw new Error('Actions cache grant unavailable');
  const generation = env.GITHUB_RUN_ID.padStart(20, '0') + env.GITHUB_RUN_ATTEMPT.padStart(4, '0') + Date.now().toString();
  const grant = Object.fromEntries(cacheEnv.filter(k => env[k]).map(k => [k, env[k]]));
  const parameter = `/migrations/cache-grants/${env.GITHUB_RUN_ID}-${env.GITHUB_RUN_ATTEMPT}-${randomUUID()}`;
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'cache-grant-'));
  let id;
  let done = false;
  let interrupted = false;
  const interrupt = () => { interrupted = true; };
  process.once('SIGTERM', interrupt);
  process.once('SIGINT', interrupt);
  try {
    // Pass the secret by a 0600 file. It must never appear in process arguments or CodeBuild metadata.
    const file = path.join(dir, 'grant.json');
    const payload = JSON.stringify({...grant, generation});
    if (Buffer.byteLength(payload) > 8192) throw new Error('Publication grant exceeds Parameter Store capacity');
    await fs.writeFile(file, payload, {mode: 0o600});
    await aws('ssm', 'put-parameter', '--name', parameter, '--type', 'SecureString', '--tier',
      Buffer.byteLength(payload) > 4096 ? 'Advanced' : 'Standard', '--value', `file://${file}`);
    await fs.rm(file);
    const variables = {CI_CACHE_MAIN_SHA: env.CI_CACHE_MAIN_SHA, CI_CACHE_MODE: env.CI_CACHE_SEED === 'true' ? 'both' : 'publish', CI_CACHE_GRANT_PARAMETER: parameter};
    if (key) variables.CI_CACHE_RECEIPT_KEY = key;
    if (native) {
      variables.CI_CACHE_BUILD_ID = native;
      variables.CI_CACHE_RECEIPT_SHA = env.CI_CACHE_RECEIPT_SHA || env.CI_CACHE_MAIN_SHA;
    }
    const overrides = Object.entries(variables).map(([name, value]) => ({name, value, type: 'PLAINTEXT'}));
    id = (await aws('codebuild', 'start-build', '--project-name', project, '--environment-variables-override', JSON.stringify(overrides))).build.id;
    if (!id.startsWith(project + ':')) throw new Error('Unexpected cache worker');
    console.log(`Started Oregon cache publisher ${id}`);
    while (!interrupted) {
      const build = (await aws('codebuild', 'batch-get-builds', '--ids', id)).builds[0];
      if (build.buildComplete) {
        done = true;
        if (build.buildStatus !== 'SUCCEEDED') throw new Error(`Cache publication ${build.buildStatus} in ${id}`);
        console.log('Cache publication succeeded.');
        return;
      }
      await delay(5000);
    }
    throw new Error('Cache publisher interrupted');
  } finally {
    process.off('SIGTERM', interrupt); process.off('SIGINT', interrupt);
    if (id && !done) await aws('codebuild', 'stop-build', '--id', id).catch(() => {});
    await fs.rm(dir, {recursive: true, force: true});
    await aws('ssm', 'delete-parameter', '--name', parameter).catch(error => {
      if (!error.message.includes('ParameterNotFound')) throw error;
    });
  }
}

async function main(args) {
  if (args[0] === 'cloud-worker') return cloudWorker();
  if (args[0] === 'launch') return launchCloud();
  if (args[0] === 'native-worker' && args[1] === '--' && args.length >= 3) return nativeWorker(args[2], args.slice(3));
  if (args[0] === 'record-worker' && args[1] === '--' && args.length >= 3) {
    return serve(await openLocalRecorder({receiptFile: process.env.CI_DEPENDENCY_RECEIPT}), 'record',
      env => run(args[2], args.slice(3), {...process.env, ...env}));
  }
  throw new Error('Expected cloud-worker, launch, or record-worker -- COMMAND [ARGS]');
}
if (require.main === module) main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = {launchCloud, receiptPath, receiptPattern, run, serve};
