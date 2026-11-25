#!/usr/bin/env node
'use strict';

const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {imageReference} = require('../dependency-cache/client-routing.cjs');

function route(tool, args, endpoint) {
  const proxy = new URL(endpoint);
  if (proxy.protocol !== 'http:' || proxy.hostname !== '127.0.0.1' || !proxy.port ||
      proxy.pathname !== '/' || proxy.search || proxy.hash || proxy.username || proxy.password) {
    throw new Error('Native OCI recording requires the local dependency proxy');
  }
  if (tool === 'crane' && ['copy', 'digest'].includes(args[0]) && args[1] && !args[1].startsWith('-')) {
    const source = imageReference(args[1]);
    if (source) {
      const separator = source.tag.startsWith('sha256:') ? '@' : ':';
      return [args[0], `${proxy.host}/${source.prefix}${separator}${source.tag}`, ...args.slice(2)];
    }
  }
  if (tool === 'helm' && args[0] === 'pull' && args[1]?.startsWith('oci://')) {
    const source = imageReference(args[1].slice('oci://'.length));
    if (source) return ['pull', `oci://${proxy.host}/${source.prefix}`, '--plain-http', ...args.slice(2)];
  }
  return args;
}

if (require.main === module) {
  const tool = path.basename(process.argv[1]);
  if (!['crane', 'helm'].includes(tool)) throw new Error('Expected a crane or helm wrapper');
  const args = route(tool, process.argv.slice(2), process.env.CI_DEPENDENCY_PROXY_URL);
  const result = spawnSync(`/usr/local/bin/${tool}`, args, {stdio: 'inherit'});
  if (result.error) throw result.error;
  if (result.signal) process.kill(process.pid, result.signal);
  else process.exitCode = result.status;
}

module.exports = {route};
