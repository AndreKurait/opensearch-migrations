#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
import socket
for endpoint in [('1.1.1.1',443), ('169.254.169.254',80), ('10.0.2.2',22)]:
    try:
        sock = socket.create_connection(endpoint, timeout=2)
    except OSError:
        continue
    sock.close()
    raise SystemExit('A guest TCP connection reached a forbidden destination')
PY
# HTTP status proves the one explicit virtual network forward is attached.
curl -fsS --max-time 5 http://127.0.0.1:7777/v2/ >/dev/null
docker info --format '{{.ServerVersion}}'
