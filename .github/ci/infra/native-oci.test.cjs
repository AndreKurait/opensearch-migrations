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
    ['helm', ['upgrade', 'migration', 'chart']]]) {
    assert.deepEqual(route(tool, args, loopback), args);
  }
});

test('HTTP chart pulls use the pinned local archive and reject unrecognized versions', () => {
  const args = ['pull', 'argo-workflows', '--repo', 'https://argoproj.github.io/argo-helm', '--version', '1.0.5'];
  assert.deepEqual(route('helm', args, loopback),
    ['pull', `${loopback}/helm/argo-workflows/argo-workflows-1.0.5.tgz`, '--version', '1.0.5']);
  assert.throws(() => route('helm', [...args.slice(0, -1), '1.0.6'], loopback), /Missing pinned/);
});

test('every image in the EKS mirror manifest uses the dependency proxy', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const manifest = fs.readFileSync(path.resolve(__dirname,
    '../../../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/infra/mirror/private-ecr-manifest.yaml'), 'utf8');
  const images = [...manifest.split('\nimages:\n')[1].matchAll(/^  - "([^"|]+)"$/gm)];
  assert.ok(images.length > 0);
  for (const [, image] of images) {
    assert.match(route('crane', ['copy', image, 'private.example/test:latest'], loopback)[1],
      /^127\.0\.0\.1:31234\//, image);
  }
});
