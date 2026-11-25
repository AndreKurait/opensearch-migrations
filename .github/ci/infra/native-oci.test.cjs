'use strict';

const {strict: assert} = require('node:assert');
const test = require('node:test');
const {route} = require('./native-oci.cjs');
const loopback = 'http://127.0.0.1:31234';

test('native mirrors record only supported upstream reads and keep private operations untouched', () => {
  const target = 'private.registry.example/mirror:one';
  const digest = 'sha256:' + 'a'.repeat(64);
  assert.deepEqual(route('crane', ['copy', `quay.io/owner/image@${digest}`, target], loopback),
    ['copy', `127.0.0.1:31234/quay/owner/image@${digest}`, target]);
  assert.deepEqual(route('crane', ['digest', 'busybox:1'], loopback), ['digest', '127.0.0.1:31234/dockerhub/library/busybox:1']);
  assert.deepEqual(route('helm', ['pull', 'oci://public.ecr.aws/acme/one', '--version', '1.2'], loopback),
    ['pull', 'oci://127.0.0.1:31234/ecrpublic/acme/one', '--plain-http', '--version', '1.2']);
  for (const [tool, args] of [['crane', ['auth', 'login', 'public.ecr.aws', '-u', 'AWS', '--password-stdin']],
    ['crane', ['digest', target]], ['crane', ['copy', target, target]], ['helm', ['push', 'chart.tgz', 'oci://' + target]],
    ['helm', ['pull', 'chart', '--repo', 'https://charts.example']], ['helm', ['upgrade', 'migration', 'chart']]]) {
    assert.deepEqual(route(tool, args, loopback), args);
  }
});
