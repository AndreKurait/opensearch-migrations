"""Expose the dependency proxy through Terraform's HTTPS network mirror protocol."""
import json
import os
import re
import shutil
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

PROXY = os.environ['CI_DEPENDENCY_PROXY_URL']


class Mirror(BaseHTTPRequestHandler):
    def do_GET(self):
        match = re.fullmatch(r'/registry\.terraform\.io/hashicorp/([a-z-]+)/(index|\d+\.\d+\.\d+)\.json', self.path)
        try:
            if match:
                provider, version = match.groups()
                base = f'{PROXY}/terraform-registry/{provider}'
                if version == 'index':
                    with urlopen(f'{base}/versions') as response:
                        data = {'versions': {entry['version']: {} for entry in json.load(response)['versions']}}
                else:
                    with urlopen(f'{base}/{version}/download/linux/amd64') as response:
                        checksum = json.load(response)['shasum']
                    if not re.fullmatch('[0-9a-f]{64}', checksum):
                        raise ValueError('Invalid package checksum')
                    archive = f'/terraform-provider/{provider}/{version}/linux_amd64/{checksum}'
                    data = {'archives': {'linux_amd64': {'url': archive, 'hashes': ['zh:' + checksum]}}}
                body = json.dumps(data).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif re.fullmatch(r'/terraform-provider/[a-z-]+/\d+\.\d+\.\d+/linux_amd64/[0-9a-f]{64}', self.path):
                with urlopen(PROXY + self.path) as response:
                    self.send_response(200)
                    self.send_header('Content-Length', response.headers['Content-Length'])
                    self.end_headers()
                    shutil.copyfileobj(response, self.wfile)
            else:
                self.send_error(404)
        except HTTPError as error:
            self.send_error(error.code)
        except (ValueError, KeyError, OSError) as error:
            self.send_error(502, str(error))


if __name__ == '__main__':
    cert, key, config = sys.argv[1:]
    server = ThreadingHTTPServer(('127.0.0.1', 0), Mirror)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    Path(config).write_text(
        'provider_installation {\n  network_mirror {\n    url = "https://127.0.0.1:%d/"\n  }\n}\n' % server.server_port
    )
    server.serve_forever()
