'use strict';
const {execFile} = require('node:child_process');
const https = require('node:https');
const {isIP} = require('node:net');
const path = require('node:path');
const {Readable} = require('node:stream');
const {promisify} = require('node:util');
const run = promisify(execFile);
const onHostedMac = () => process.platform === 'darwin' && process.env.RUNNER_ENVIRONMENT === 'github-hosted';

// Both the firewall and TLS socket use this DNS answer. The request still verifies the original
// hostname and preserves its HTTPS Host and SNI. Redirects never receive the runtime credential.
async function requestGitHub(url, phase, {method = 'GET', body, headers, signal} = {}) {
  if (url.protocol !== 'https:' || url.port || url.username || url.password) throw new Error('Invalid cache backend');
  const helper = path.resolve(__dirname, '../network/darwin_cache_route.py');
  const {stdout} = await run('/usr/bin/python3', ['-I', helper, phase, url.hostname], {timeout: 10_000});
  const addresses = stdout.trim().split('\n');
  if (!addresses.length || !addresses.every(ip => isIP(ip))) throw new Error('Invalid GitHub cache resolution');
  for (const [offset, address] of addresses.entries()) {
    try {
      const response = await new Promise((resolve, reject) => {
        const req = https.request(url, {
          agent: false, family: isIP(address), servername: url.hostname, method, headers, signal,
          lookup: (name, opts, cb) => name === url.hostname ? cb(null, address, isIP(address)) : cb(new Error('Unexpected TLS host')),
        });
        const timer = setTimeout(() => req.destroy(Object.assign(new Error('Cache connection timed out'), {code: 'ETIMEDOUT'})), 9000);
        req.once('response', res => { clearTimeout(timer); resolve(res); });
        req.once('error', error => { clearTimeout(timer); reject(error); });
        req.end(body);
      });
      return new Response(Readable.toWeb(response), {status: response.statusCode});
    } catch (error) {
      if (offset === addresses.length - 1 || !['ETIMEDOUT','ENETUNREACH','EHOSTUNREACH','ECONNRESET'].includes(error.code)) throw error;
    }
  }
}

module.exports = {onHostedMac, requestGitHub};
