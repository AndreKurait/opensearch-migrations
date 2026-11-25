'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { setupClients, engineAdapter, bridgeRelay, imageReference, containersConfig, buildkitConfig, k3sConfig } = require('./client-routing.cjs');
const exec = promisify(execFile);

async function serve(server, ...args) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(...args, resolve); });
  return async () => { await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }); };
}

function get(opts, body) {
  return new Promise((resolve, reject) => {
    const r = http.request(opts, resp => {
      const chunks = []; resp.on('data', c => chunks.push(c));
      resp.on('end', () => resolve([resp.statusCode, Buffer.concat(chunks).toString()]));
    });
    r.on('error', reject); r.end(body);
  });
}

test('all native origin mapping formats agree and cleanup leaves read jobs without a Docker socket', async () => {
  const clients = await setupClients({ url: 'http://127.0.0.1:12345', mode: 'read' });
  try {
    assert.equal(clients.env.MAVEN_REPOSITORY_URL, 'http://127.0.0.1:12345/maven/');
    assert.equal(clients.env.DOCKER_HOST, undefined);
    if (clients.env.CI_DEPENDENCY_GRADLE_WRAPPER_JAR) {
      const props = await fs.readFile(clients.env.CI_DEPENDENCY_GRADLE_WRAPPER_JAR.replace(/\.jar$/, '.properties'), 'utf8');
      assert.match(props, /^distributionUrl=http:\/\/127\.0\.0\.1:12345\/raw\/gradle\/gradle-[\w.+-]+\.zip$/m);
      assert.match(props, /^networkTimeout=120000$/m);
    }
    const config = await fs.readFile(clients.env.CONTAINERS_REGISTRIES_CONF, 'utf8');
    assert.equal(config, containersConfig('127.0.0.1', '12345'));
    for (const [host,id] of [['ghcr.io','ghcr'],['quay.io','quay'],['registry.k8s.io','k8s'],['public.ecr.aws','ecrpublic'],['docker.io','dockerhub']]) {
      assert.match(config, new RegExp(`location = "127.0.0.1:12345/${id}"`));
      assert.match(buildkitConfig('172.17.0.1', '12345'), new RegExp(`mirrors = \\["172.17.0.1:12345/${id}"\\]`));
      assert.equal(imageReference(`${host}/team/image:latest`).prefix, `${id}/team/image`);
    }
    assert.equal(imageReference('busybox').prefix, 'dockerhub/library/busybox');
    const digest = `sha256:${'a'.repeat(64)}`;
    assert.deepEqual(imageReference(`gcr.io/distroless/static:nonroot@${digest}`), {
      prefix: 'distroless/distroless/static', tag: digest, repository: 'gcr.io/distroless/static',
    });
    assert.throws(() => imageReference('gcr.io/other/image:latest'), /Unsupported public OCI repository/);
    assert.equal(imageReference('private-registry.example/team/image:main'), null);
  } finally { await clients.close(); }
  await assert.rejects(fs.access(clients.env.CONTAINERS_REGISTRIES_CONF), {code:'ENOENT'});
  await assert.rejects(setupClients({url:'http://example.org:9999', mode:'record'}), /loopback/);
});

test('bridge accepts only canonical public OCI reads and never forwards registry credentials', async t => {
  const seen = [];
  const proxy = http.createServer((req, res) => { seen.push([req.url, req.headers]); res.end('manifest'); });
  t.after(await serve(proxy, 0, '127.0.0.1'));
  const relay = await bridgeRelay(new URL(`http://127.0.0.1:${proxy.address().port}`), '127.0.0.1');
  t.after(relay.close);
  const options = { host:'127.0.0.1',port:relay.port,headers:{authorization:'secret','x-registry-auth':'secret'} };
  const route = '/v2/ghcr/org/image/manifests/latest';
  assert.deepEqual(await get({...options,path:route+'?ns=ghcr.io'}), [200,'manifest']);
  assert.equal(seen[0][0],route);
  assert.equal(seen[0][1].authorization,undefined);
  assert.equal(seen[0][1]['x-registry-auth'],undefined);
  for (const request of [{path:'/raw/kind/file'},{path:route,method:'POST'},{path:route+'?other=host'}]) {
    assert.equal((await get({...options,...request}))[0],400);
  }
  assert.equal(seen.length,1);
});

test('a real Docker CLI talks solely to an isolated Unix-socket fixture and preserves non-Hub tag and digest pulls', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'routing-engine-test-'));
  t.after(() => fs.rm(dir, {recursive:true,force:true}));
  const daemon = path.join(dir,'fixture.sock');
  const listener = path.join(dir,'routed.sock');
  const seen = [];
  const fixture = http.createServer(async (req,res) => {
    const u = new URL(req.url,'http://localhost');
    seen.push([req.method,u,req.headers]);
    if (u.pathname === '/_ping' || u.pathname.endsWith('/_ping')) { res.setHeader('Api-Version','1.44'); res.end('OK'); return; }
    if (u.pathname.endsWith('/images/create')) { res.setHeader('content-type','application/json');res.end('{"status":"pulled"}\n');return; }
    if (u.pathname.endsWith('/tag')) { res.writeHead(201).end();return; }
    if (u.pathname.endsWith('/containers/create')) {
      let buf='';for await(const part of req) buf+=part; res.end(buf);return;
    }
    res.setHeader('content-type','application/json');res.end('[]');
  });
  t.after(await serve(fixture,daemon));
  const k3sFile = path.join(dir, 'k3s-registries.yaml');
  const adapter = await engineAdapter(daemon, listener, '127.0.0.1:9876', k3sFile); t.after(adapter.close);
  const env = {...process.env,DOCKER_HOST:`unix://${listener}`,DOCKER_API_VERSION:'1.44',DOCKER_CONFIG:dir};
  for (const img of ['ghcr.io/repo/image:test','public.ecr.aws/team/repo:cached']) {
    const p = await exec('docker',['pull',img],{env}); assert.match(p.stdout,/pulled/);
  }
  const creates=seen.filter(([,u])=>u.pathname.endsWith('/images/create'));
  assert.deepEqual(creates.map(([,u])=>u.searchParams.get('fromImage')),['127.0.0.1:9876/ghcr/repo/image','127.0.0.1:9876/ecrpublic/team/repo']);
  const tags=seen.filter(([,u])=>u.pathname.endsWith('/tag'));
  assert.deepEqual(tags.map(([,u])=>u.searchParams.get('repo')),['ghcr.io/repo/image','public.ecr.aws/team/repo']);
  const hash='sha256:'+ 'a'.repeat(64);
  await get({socketPath:listener,method:'POST',path:'/v1.44/images/create?fromImage=kindest/node&tag='+hash,headers:{'x-registry-auth':'must-not-forward'}});
  assert.equal(seen.find(([,u])=>u.searchParams.get('tag')===hash)[2]['x-registry-auth'],undefined);
  const [, body]=await get({socketPath:listener,method:'POST',path:'/v1.44/containers/create'},JSON.stringify({Image:'kindest/node@'+hash}));
  assert.equal(JSON.parse(body).Image,'127.0.0.1:9876/dockerhub/kindest/node@'+hash);
  await get({socketPath:listener,path:'/v1.44/images/'+encodeURIComponent('kindest/node@'+hash)+'/json'});
  assert.match(decodeURIComponent(seen.at(-1)[1].pathname),/127\.0\.0\.1:9876\/dockerhub\/kindest\/node@sha256/);
  const k3s = {Image:'rancher/k3s:v1.31.6-k3s1',Cmd:['server','--disable=traefik'],HostConfig:{Binds:['/tmp/work:/work:ro']}};
  const opts = {socketPath:listener,method:'POST',path:'/v1.44/containers/create'};
  const actual = JSON.parse((await get(opts,JSON.stringify(k3s)))[1]);
  assert.deepEqual(actual.Cmd, [...k3s.Cmd,'--disable-default-registry-endpoint']);
  assert.deepEqual(actual.HostConfig.Binds, [...k3s.HostConfig.Binds,`${k3sFile}:/etc/rancher/k3s/registries.yaml:ro`]);
  assert.deepEqual(JSON.parse((await get(opts, JSON.stringify(actual)))[1]),actual);
  const mirrors=JSON.parse(k3sConfig('172.17.0.1',12345)).mirrors;
  assert.deepEqual(mirrors['quay.io'],{endpoint:['http://172.17.0.1:12345'],rewrite:{'^(.*)$':'quay/$1'}});
});


test('kind containerd config maps each public registry to the corresponding private cache API root', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'routing-kind-test-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const script = path.resolve(__dirname, '../../scripts/registry-cache.sh');
  const mock = path.join(dir, 'docker');
  await fs.writeFile(mock, `#!/usr/bin/env bash
set -eu
[[ "$1" == exec ]]
shift
if [[ "$1" != -i ]]; then exit 0; fi
shift
node="$1"
shift
[[ "$1" == cp && "$2" == /dev/stdin ]]
host="$(basename "$(dirname "$3")")"
cat > "$ROUTING_TEST_OUTPUT/$node-$host.toml"
`, { mode: 0o700 });
  const env = { ...process.env, CI_DEPENDENCY_REGISTRY_ENDPOINT: '172.17.0.1:9521', ROUTING_TEST_OUTPUT: dir, PATH: `${dir}:${process.env.PATH}` };
  await exec('bash', [script, 'configure-dependency-nodes', 'kind-control-plane', 'kind-worker'], { env });
  for (const node of ['kind-control-plane', 'kind-worker']) {
    for (const [origin, id] of [['docker.io', 'dockerhub'], ['ghcr.io', 'ghcr'], ['quay.io', 'quay'], ['public.ecr.aws', 'ecrpublic'], ['registry.k8s.io', 'k8s'], ['mirror.gcr.io', 'gcr'], ['gcr.io', 'distroless']]) {
      const config = await fs.readFile(path.join(dir, `${node}-${origin}.toml`), 'utf8');
      assert.ok(config.includes(`[host."http://172.17.0.1:9521/v2/${id}"]`));
      assert.ok(config.includes('override_path = true'));
      assert.ok(config.includes('server = "http://127.0.0.1:1"'));
    }
  }
});


test('normal skopeo invocation uses its explicit registries.conf even when no environment override is supported', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'routing-skopeo-test-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const orig = process.env.PATH;
  try {
    await fs.writeFile(path.join(dir,'skopeo'), '#!/bin/sh\nprintf "%s\\n" "$@"\n', {mode:0o700});
    process.env.PATH = dir + path.delimiter + orig;
    const c = await setupClients({ url: 'http://127.0.0.1:9842', mode:'read' });
    try {
      const result = await exec('skopeo',['inspect','docker://ghcr.io/example/tool:1'], {env:{...process.env,...c.env}});
      assert.deepEqual(result.stdout.trim().split('\n'), ['--registries-conf',c.env.CONTAINERS_REGISTRIES_CONF,'inspect','docker://ghcr.io/example/tool:1']);
    } finally { await c.close(); }
  } finally { process.env.PATH = orig; }
});
