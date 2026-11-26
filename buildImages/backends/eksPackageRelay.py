"""Connect EKS Dockerfile package requests to the CI worker's dependency broker."""
import concurrent.futures
import http.client
import http.server
import os
import queue
import select
import shutil
import socket
import socketserver
import sys
import threading
import time
import urllib.parse


class PackageRequest(http.server.BaseHTTPRequestHandler):
    def forward(self):
        path = urllib.parse.urlsplit(self.path)
        if (path.scheme or path.netloc or path.query or path.fragment or
                not path.path.startswith(('/raw/alpine/', '/raw/al2023/', '/raw/elastic/', '/raw/elastic-legacy/')) or
                '..' in urllib.parse.unquote(path.path).split('/')):
            self.send_error(403)
            return
        broker = urllib.parse.urlsplit(os.environ['CI_DEPENDENCY_PROXY_URL'])
        upstream = http.client.HTTPConnection(broker.hostname, broker.port, timeout=180)
        try:
            upstream.request(self.command, path.path, headers={k: self.headers[k] for k in ('Range', 'If-None-Match', 'If-Modified-Since') if k in self.headers})
            response = upstream.getresponse()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() in ('content-length', 'content-type', 'content-range', 'etag', 'last-modified', 'accept-ranges'):
                    self.send_header(key, value)
            self.end_headers()
            if self.command == 'GET':
                shutil.copyfileobj(response, self.wfile)
        finally:
            upstream.close()

    do_GET = do_HEAD = forward


def lane(port):
    while True:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
                connection.settimeout(None)
                if connection.recv(1) == b'R':
                    PackageRequest(connection, ('127.0.0.1', port), None)
        except (OSError, http.client.HTTPException) as error:
            print(error, file=sys.stderr, flush=True)
            time.sleep(1)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(public_port, control_port):
    lanes = queue.Queue(32)

    class Worker(socketserver.BaseRequestHandler):
        def handle(self):
            done = threading.Event()
            lanes.put((self.request, done))
            done.wait()

    class Client(socketserver.BaseRequestHandler):
        def handle(self):
            while True:
                worker, done = lanes.get(timeout=60)
                try:
                    worker.sendall(b'R')
                    if not select.select([worker], [], [], 0)[0]:
                        break
                except OSError:
                    pass
                done.set()
            try:
                while True:
                    ready, _, _ = select.select([self.request, worker], [], [], 180)
                    if not ready:
                        return
                    for source in ready:
                        data = source.recv(65536)
                        if not data:
                            return
                        (worker if source is self.request else self.request).sendall(data)
            finally:
                done.set()

    with Server(('127.0.0.1', control_port), Worker) as control, Server(('', public_port), Client) as public:
        threading.Thread(target=control.serve_forever, daemon=True).start()
        public.serve_forever()


if __name__ == '__main__':
    if sys.argv[1] == 'pod':
        serve(int(sys.argv[2]), int(sys.argv[3]))
    else:
        with concurrent.futures.ThreadPoolExecutor(16) as pool:
            list(pool.map(lane, [int(sys.argv[2])] * 16))
