'use strict';

const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {openBuildCache} = require('./gradle-build-cache.cjs');

// Both backends verify the task output SHA-256 before Gradle sees restored bytes.
const prefix = 'gradle-outputs/v1/';
const entry = /^migrations-gradle-output-v1-([0-9a-f]{32,64})-([0-9a-f]{64})$/;

async function openAwsBuildCache({aws, bucket, write}) {
  const workspace = await fs.mkdtemp(path.join(os.tmpdir(), 'aws-gradle-cache-'));
  const getInventory = async () => {
    const tasks = new Map();
    let token;
    do {
      const page = await aws('s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', prefix,
        '--no-paginate', ...(token ? ['--continuation-token', token] : []));
      for (const {Key} of page.Contents || []) {
        const match = entry.exec(Key.slice(prefix.length));
        if (match && !tasks.has(match[1])) tasks.set(match[1], match[2]);
      }
      token = page.NextContinuationToken;
    } while (token);
    return tasks;
  };
  const toolkit = {
    async restoreCache(_paths, key, _fallback, {destination}) {
      await aws('s3api', 'get-object', '--bucket', bucket, '--key', prefix + key, destination);
      return key;
    },
    async saveCache([file], key) {
      const checksum = Buffer.from(entry.exec(key)[2], 'hex').toString('base64');
      try {
        await aws('s3api', 'put-object', '--bucket', bucket, '--key', prefix + key, '--body', path.join(workspace, file),
          '--checksum-sha256', checksum, '--server-side-encryption', 'AES256', '--if-none-match', '*');
      } catch (error) {
        if (!error.message.endsWith(': PreconditionFailed')) throw error;
      }
      return 1;
    },
  };
  const cache = await openBuildCache({mode: write ? 'record' : 'read', trusted: write, toolkit, getInventory, workspace});
  return {...cache, async close() {
    try { await cache.close(); } finally { await fs.rm(workspace, {recursive: true, force: true}); }
  }};
}

module.exports = {openAwsBuildCache};
