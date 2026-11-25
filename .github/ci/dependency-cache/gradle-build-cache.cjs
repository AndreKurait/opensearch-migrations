'use strict';

const {createHash, randomUUID} = require('node:crypto');
const {createReadStream, createWriteStream, promises: fs} = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {Transform} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const {setTimeout: delay} = require('node:timers/promises');

// Gradle hashes task inputs; the publisher hashes the serialized task output independently.
const prefix = 'migrations-gradle-output-v1-';
const route = /^\/gradle-build-cache\/([0-9a-f]{32,64})$/;
const maxBytes = 128 * 1024 ** 2;
const localLimit = 2 * 1024 ** 3;
const cachePath = key => `.migrations-public-dependency-cache-v1/gradle/${key}`;
const allowed = () => process.env.GITHUB_REF === 'refs/heads/main' && ['push','schedule','workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME);

async function inventory(repo, token, request = fetch) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repo)) throw new Error('Missing repository for Gradle cache discovery');
  const keys = new Map();
  const headers = {accept:'application/vnd.github+json'};
  if (token) headers.authorization = `Bearer ${token}`;
  const matchKey = new RegExp(`^${prefix}([a-f0-9]{32,64})-([a-f0-9]{64})$`);
  for (let page = 1; page <= 100; page++) {
    const url = new URL(`https://api.github.com/repos/${repo}/actions/caches`);
    url.search = new URLSearchParams({ref:'refs/heads/main',key:prefix,per_page:'100',page:String(page),sort:'last_accessed_at',direction:'desc'});
    const response = await request(url,{headers,redirect:'error',signal:AbortSignal.timeout(15000)});
    if (!response.ok) throw new Error(`Cannot discover protected Gradle outputs (HTTP ${response.status})`);
    const entries = (await response.json()).actions_caches;
    if (!Array.isArray(entries)) throw new Error('Invalid GitHub cache inventory');
    for (const row of entries) {
      const parsed = matchKey.exec(row.key);
      if (parsed && row.ref === 'refs/heads/main' && !keys.has(parsed[1])) keys.set(parsed[1], parsed[2]);
    }
    if (entries.length < 100) break;
  }
  return keys;
}

async function streamFile(stream, file, limit) {
  const hash = createHash('sha256');
  let bytes = 0;
  const count = new Transform({transform(chunk, encoding, next) {
    bytes += chunk.length;
    if (bytes > limit) return next(Object.assign(new Error('Gradle output exceeds size limit'), {status:413}));
    hash.update(chunk); next(null, chunk);
  }});
  await pipeline(stream, count, createWriteStream(file,{mode:0o600,flags:'wx'}));
  return {bytes, digest:hash.digest('hex')};
}

async function openBuildCache({mode, trusted = allowed(), toolkit, getInventory, workspace = process.cwd()} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'gradle-output-'));
  const write = mode === 'record' && trusted;
  const pending = new Map(), local = new Map();
  let keys, used = 0, nextWrite = 0;
  const metrics = {hits:0,misses:0,saved:0,restoredBytes:0};
  const catalog = () => keys ||= (getInventory ? getInventory() : inventory(process.env.GITHUB_REPOSITORY, process.env.INPUT_CATALOG_TOKEN));
  async function lookup(key) {
    if (local.has(key)) return local.get(key);
    const digest = (await catalog()).get(key);
    if (!digest || used + maxBytes > localLimit) return null;
    const file = path.join(dir,randomUUID());
    const entry = prefix + key + '-' + digest;
    used += maxBytes;
    let kept = 0;
    try {
      if (await toolkit.restoreCache([cachePath(key)], entry, [], {maxBytes, destination:file}) !== entry) return null;
      const st = await fs.lstat(file);
      if (!st.isFile() || st.size > maxBytes) throw new Error('Invalid Gradle output file');
      const hash = createHash('sha256');
      for await (const bytes of createReadStream(file)) hash.update(bytes);
      if (hash.digest('hex') !== digest) throw new Error('Restored Gradle output digest differs from protected main');
      kept = st.size; metrics.restoredBytes += kept;
      const item = {file, bytes:kept,digest}; local.set(key,item); return item;
    } finally { used += kept - maxBytes; if (!kept && !local.has(key)) await fs.rm(file,{force:true}); }
  }
  async function save(key, req) {
    if (local.has(key) || (await catalog()).has(key)) { req.resume(); return; }
    if (used + maxBytes > localLimit) throw Object.assign(new Error('Gradle output budget reached'),{status:413});
    used += maxBytes;
    const file = path.join(dir,randomUUID());
    const member = cachePath(key);
    const staging = path.join(workspace,member);
    let kept = 0;
    try {
      const item = await streamFile(req,file,maxBytes);
      const remote = prefix + key + '-' + item.digest;
      // Limit concurrent publishers' total traffic; ordinary reads of absent tasks do no archive lookups.
      const when = Math.max(nextWrite,Date.now()); nextWrite = when + 1000;
      if (when > Date.now()) await delay(when - Date.now());
      await fs.mkdir(path.dirname(staging),{recursive:true,mode:0o700});
      await fs.copyFile(file,staging);
      if (await toolkit.saveCache([member], remote) < 0 && await toolkit.restoreCache([member],remote,[],{lookupOnly:true}) !== remote) throw new Error('Gradle output publication failed');
      kept = item.bytes; metrics.saved++; (await catalog()).set(key,item.digest); local.set(key,{file,...item});
    } finally { used += kept - maxBytes; await fs.rm(staging,{force:true}); if (!local.has(key)) await fs.rm(file,{force:true}); }
  }
  async function serial(key, fn) {
    const prior = pending.get(key);
    const operation = (prior || Promise.resolve()).catch(()=>{}).then(fn);
    pending.set(key,operation);
    try { return await operation; } finally { if (pending.get(key) === operation) pending.delete(key); }
  }
  async function handle(req, res) {
    const match = route.exec(req.url); if (!match) return false;
    const key = match[1];
    try {
      if (req.method === 'PUT') {
        if (!write) { req.resume(); res.writeHead(403).end(); return true; }
        await serial(key,()=>save(key,req)); res.writeHead(200).end(); return true;
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return true; }
      const item = await serial(key,()=>lookup(key));
      if (!item) { metrics.misses++; res.writeHead(404).end(); return true; }
      metrics.hits++; res.writeHead(200,{'content-length':item.bytes,'content-type':'application/octet-stream'});
      if (req.method === 'HEAD') res.end(); else await pipeline(createReadStream(item.file),res);
    } catch (e) { if (res.headersSent) res.destroy(); else res.writeHead(e.status || 502).end(); }
    return true;
  }
  return {handle,write,metrics,async close() { await Promise.allSettled(pending.values()); await fs.rm(dir,{recursive:true,force:true}); }};
}
module.exports = {openBuildCache,inventory};
