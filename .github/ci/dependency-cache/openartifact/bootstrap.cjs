'use strict';
const { execFile } = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const { promisify } = require('node:util');
const images = require('./images.json');
const exec = promisify(execFile);

async function serviceImages(mode) {
  const key = process.env.CI_SERVICE_IMAGE_CACHE_KEY;
  if (!key) return;
  const dir = path.join(process.env.RUNNER_TEMP, 'dependency-service-images');
  const archive = path.join(dir, 'images.tar');
  const ids = Object.fromEntries(Object.entries(images).map(([name, pin]) => [name, pin[process.env.RUNNER_ARCH]]));
  if (!Object.values(ids).every(id => /^sha256:[0-9a-f]{64}$/.test(id))) throw new Error('Unsupported service image architecture');
  const cache = await import('@actions/cache');
  if (await cache.restoreCache([dir], key)) {
    await exec('docker', ['load', '--input', archive]);
  } else {
    if (process.env.CI_DEPENDENCY_STRICT_READ === '1') throw new Error(`Missing service image cache ${key}`);
    for (const [name, image] of Object.entries(images)) {
      await exec('docker', ['pull', image.pin]);
      const result = await exec('docker', ['image', 'inspect', '--format', '{{.Id}}', image.pin]);
      if (result.stdout.trim() !== ids[name]) throw new Error(`Unexpected image ID for ${name}`);
    }
    if (mode === 'record') {
      await fs.mkdir(dir, {recursive: true});
      await exec('docker', ['save', '--output', archive, ...Object.values(ids)]);
      await cache.saveCache([dir], key);
    }
  }
  return ids;
}
module.exports = { serviceImages };
