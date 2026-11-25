'use strict';

const {createHash} = require('node:crypto');
const {createWriteStream} = require('node:fs');
const {appendFile, mkdtemp, rm} = require('node:fs/promises');
const {join} = require('node:path');
const {Readable, Transform} = require('node:stream');
const {pipeline} = require('node:stream/promises');

// Published SHA-256 values for the JDK tarballs at https://github.com/corretto/corretto-21/releases/tag/21.0.13.1.2
const release = '21.0.13.1.2';
const checksums = {
  'linux-x64': '2aaa2a9a43143b20ff0b28c8d0da27407b97dc40b160d9a2f60a26965815e948',
  'linux-aarch64': '55751c47c7e3be3b33af43ccdcf3a6f4814d308390b7400477594d421bd16a53',
  'macosx-x64': '83095d9de2fe412151eede49e0373eeb5290bc0b82cc60cedb35e1da2d0a5ecc',
  'macosx-aarch64': '3e566c5b2760dc4f2199ac68b09a1b7c5381f228a336f3514a1aad4c113d474a',
};

function archiveFor(platform, arch) {
  const distribution = `${platform === 'darwin' ? 'macosx' : platform}-${arch === 'arm64' ? 'aarch64' : arch}`;
  if (!Object.hasOwn(checksums, distribution)) throw new Error(`Unsupported Corretto JDK platform ${platform}/${arch}`);
  const filename = `amazon-corretto-${release}-${distribution}.tar.gz`;
  return {filename, digest: checksums[distribution], key: `/raw/corretto/resources/${release}/${filename}`};
}

async function fetchJdk(proxy, dir, platform = process.platform, arch = process.arch) {
  const {filename, digest, key} = archiveFor(platform, arch);
  const url = new URL(proxy);
  if (url.protocol !== 'http:' || !['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname) ||
      url.pathname !== '/' || url.search || url.hash || url.username || url.password) throw new Error('Expected a loopback dependency proxy');
  const response = await fetch(new URL(key, url), {redirect: 'error'});
  if (!response.ok) throw new Error(`Corretto download returned ${response.status} for ${key}`);
  const work = await mkdtemp(join(dir, 'corretto-'));
  const archive = join(work, filename);
  const hash = createHash('sha256');
  const meter = new Transform({transform(chunk, encoding, done) { hash.update(chunk); done(null, chunk); }});
  try {
    await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(archive, {flags: 'wx', mode: 0o600}));
    if (hash.digest('hex') !== digest) throw new Error(`Corretto archive disagrees with its published SHA-256 for ${key}`);
    return archive;
  } catch (error) {
    await rm(work, {recursive: true, force: true});
    throw error;
  }
}

async function selectJdk() {
  const archive = await fetchJdk(process.env.CI_DEPENDENCY_PROXY_URL, process.env.RUNNER_TEMP);
  await appendFile(process.env.GITHUB_OUTPUT, `archive=${archive}\njava-version=${release.split('.').slice(0, 3).join('.')}\n`);
}

if (require.main === module) selectJdk().catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = {archiveFor, fetchJdk};
