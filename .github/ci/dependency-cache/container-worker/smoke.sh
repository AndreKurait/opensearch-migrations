#!/usr/bin/env bash
set -euo pipefail
# The host prepares the read-only image before the candidate guest boots.
TESTCONTAINERS_RYUK_DISABLED=true /opt/container-worker/bin/python - <<'PY'
import os
from testcontainers.core.container import DockerContainer

image = '10.0.2.100:7777/dockerhub/migrations/worker-probe@sha256:' + os.environ['CI_CONTAINER_PROBE_DIGEST']
with DockerContainer(image).with_command('sleep 45') as container:
    native = container.get_wrapped_container()
    assert image in native.image.attrs['RepoDigests'], 'Testcontainers image digest mismatch'
    assert native.exec_run(['sh','-c','printf nested-docker']).output == b'nested-docker'
    # Fetch the permitted forwarded endpoint to independently demonstrate this container has functioning networking.
    ok = native.exec_run(['wget','-q','-T','3','-O','-','http://10.0.2.100:7777/v2/'])
    assert ok.exit_code == 0, 'Nested Docker cannot reach the job cache'
    for endpoint in ['http://1.1.1.1/', 'http://169.254.169.254/latest/meta-data/']:
        blocked = native.exec_run(['wget','-q','-T','2','-O','/dev/null',endpoint])
        assert blocked.exit_code != 0, 'Nested Docker reached a forbidden network endpoint'
print('Guest-native Testcontainers verified the image, executed a process and confirmed outbound blocking')
PY
