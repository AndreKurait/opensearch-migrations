'use strict';
const {publish} = require('../../ci/dependency-cache/buildkit-cache.cjs');
const {loadToolkit} = require('../../ci/dependency-cache/github-store.cjs');
(async () => {
  if (process.env.GITHUB_REF !== 'refs/heads/main' || !['push','schedule','workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME)) throw new Error('Only protected main publishes BuildKit caches');
  await publish(process.env.EXTERNAL_REGISTRY_VOLUME, await loadToolkit());
})().catch(error => { console.error(error.message); process.exitCode = 1; });
