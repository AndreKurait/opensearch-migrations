'use strict';

const fs = require('node:fs/promises');
const http = require('node:http');
const net = require('node:net');
const os = require('node:os');
const { spawn } = require('node:child_process');
const { pipeline } = require('node:stream/promises');

async function rejectEgress() {
  if (process.getuid() === 0) throw new Error('Offline candidate must use an unprivileged UID');
  const interfaces = Object.keys(os.networkInterfaces());
  if (interfaces.some(name => name !== 'lo')) throw new Error('Unexpected egress-capable network interface');
  const status = await fs.readFile('/proc/self/status','utf8');
  if (Object.keys(process.env).some(k => /^(AWS_|ACTIONS_|GITHUB_(?:TOKEN|ENV|OUTPUT|STATE)$)/.test(k))) {
    throw new Error('Offline test received a host credential or workflow command environment');
  }
  if (!/^CapEff:\s+0+$/m.test(status)) throw new Error('Offline candidate retained Linux capabilities');
  for (const name of ['/var/run/docker.sock', '/run/docker.sock', '/run/containerd/containerd.sock']) {
    try { await fs.access(name); throw new Error(`Detected runtime socket ${name}`); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  await new Promise((resolve, reject) => {
    const s = net.createConnection(443, '1.1.1.1');
    s.once('connect', () => { s.destroy(); reject(new Error('Direct Internet connection succeeded')); });
    s.once('error', err => /ENETUNREACH|EHOSTUNREACH|EACCES|EPERM/.test(err.code) ? resolve() : reject(err));
    s.setTimeout(3000, () => { s.destroy(); reject(new Error('Direct Internet probe timed out without a confirmed denial')); });
  });
}

async function run() {
  await rejectEgress();
  const broker = '/run/dependency-cache/cache.sock';
  const proxy = http.createServer((req,res) => {
    if (!['GET','HEAD'].includes(req.method) || !req.url.startsWith('/') || req.url.startsWith('//')) { res.writeHead(400).end(); return; }
    // Registry metadata must point at this guest's loopback port, never the host broker port.
    const headers = { host: `127.0.0.1:${proxy.address().port}` };
    for (const h of ['accept','range','if-range','if-none-match']) if (req.headers[h]) headers[h] = req.headers[h];
    const peer = http.request({socketPath:broker,path:req.url,method:req.method,headers}, async p => {
      try { res.writeHead(p.statusCode,p.headers); await pipeline(p,res); } catch { res.destroy(); }
    });
    peer.on('error', () => res.writeHead(502).end()); req.on('aborted', () => peer.destroy()); peer.end();
  });
  await new Promise((resolve,reject) => { proxy.once('error',reject); proxy.listen(0,'127.0.0.1',resolve); });
  const url = `http://127.0.0.1:${proxy.address().port}`;
  try {
    const ping = await fetch(url+'/v2/'); if (ping.status !== 200) throw new Error(`Cache broker probe returned ${ping.status}`);
    console.log('Offline sandbox verified direct Internet denial, zero Linux capabilities, and an isolated cache broker.');
    const env = {...process.env, CI_DEPENDENCY_PROXY_URL:url, CI_DEPENDENCY_CACHE_URL:url, MAVEN_REPOSITORY_URL:url+'/maven/',
      npm_config_registry:url+'/npm/', npm_config_audit:'false', npm_config_update_notifier:'false', PIP_INDEX_URL:url+'/pypi/simple/',
      PIP_EXTRA_INDEX_URL:'',PIPENV_PYPI_MIRROR:url+'/pypi/simple/',PIP_DISABLE_PIP_VERSION_CHECK:'1',SKOPEO_BIN:'/tools/bin/skopeo'};
    const wrapperDir = '/home/worker/wrapper/gradle/wrapper';
    await fs.mkdir(wrapperDir, {recursive:true});
    const source = '/source/gradle/wrapper/gradle-wrapper';
    const target = wrapperDir + '/gradle-wrapper';
    const raw = await fs.readFile(source+'.properties','utf8');
    const pattern = /^distributionUrl=https\\?:\/\/services\.gradle\.org\/distributions\/(gradle-[a-zA-Z0-9.+-]+\.zip)$/m;
    if (!pattern.test(raw)) throw new Error('Unrecognized wrapper distribution');
    await fs.copyFile(source+'.jar',target+'.jar');
    await fs.writeFile(target+'.properties',raw.replace(pattern,`distributionUrl=${url}/raw/gradle/$1`));
    env.CI_DEPENDENCY_GRADLE_WRAPPER_JAR = target+'.jar';
    if (!env.GRADLE_BIN) {
      const launcher = '/home/worker/wrapper/gradle-run';
      await fs.writeFile(launcher,'#!/bin/sh\nexec /tools/jdk/bin/java -classpath "$CI_DEPENDENCY_GRADLE_WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"\n',{mode:0o700});
      env.GRADLE_BIN = launcher;
    }
    // Build inputs are read-only. Tests that generate build outputs may use their writable snapshot in /work.
    await fs.cp('/source','/work/source',{recursive:true,verbatimSymlinks:true});
    const [command,...args] = process.argv.slice(2);
    const code = await new Promise((resolve,reject) => {
      const child=spawn(command,args,{env,cwd:'/work/source',stdio:'inherit'});child.once('error',reject);
      child.once('exit',(status,signal) => signal ? reject(new Error(`Offline command received ${signal}`)) : resolve(status));
    });
    process.exitCode=code;
  } finally { proxy.closeAllConnections(); await new Promise(resolve=>proxy.close(resolve)); }
}
run().catch(error=>{console.error(error.message);process.exitCode=1;});
