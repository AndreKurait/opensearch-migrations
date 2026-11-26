#!/usr/bin/env python3
"""Install the hosted macOS PF policy and run a Gradle candidate with a separate Seatbelt profile."""

import ipaddress
import json
import http.server
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import urllib.parse
import urllib.request

PF = '/sbin/pfctl'
GUEST_UID = 47577  # An unmapped, non-admin UID on the disposable macOS worker.
ANCHOR = 'com.apple/migrations-egress'
OS_READ = [
    '/bin',
    '/usr/bin',
    '/usr/lib',
    '/usr/share',
    '/usr/libexec',
    '/sbin',
    '/System',
    '/Library/Apple',
    '/Library/Developer',
    '/private/etc',
    '/private/preboot/Cryptexes/OS',
    '/private/var/db/dyld',
    '/dev',
    '/opt/homebrew',
]


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def addresses(host):
    try:
        return {
            str(ipaddress.ip_address(row[4][0]))
            for row in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
            if ipaddress.ip_address(row[4][0]).is_global
        }
    except socket.gaierror:
        return set()


def configured_hosts():
    hosts = set(os.environ['PUBLIC_ENDPOINTS'].split())
    for endpoint in hosts:
        if not re.fullmatch(r'(?:[a-z0-9-]+\.)+[a-z0-9-]+:(?:80|443)', endpoint):
            raise ValueError(f'Unexpected endpoint {endpoint}')
    # Runners share an egress IP. Authenticate the trusted, read-only /meta request to avoid the
    # small anonymous per-IP rate limit. No candidate process receives this environment variable.
    token = os.environ['GITHUB_META_TOKEN']
    request = urllib.request.Request('https://api.github.com/meta', headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request, context=ssl.create_default_context(), timeout=10) as result:
        metadata = json.load(result)
    platform = metadata['domains']['actions']
    for host in platform:
        if re.fullmatch(r'[a-z0-9.-]+', host):
            hosts.add(host + ':443')
    for name in [
        'ACTIONS_CACHE_URL',
        'ACTIONS_RESULTS_URL',
        'ACTIONS_RUNTIME_URL',
        'ACTIONS_ID_TOKEN_REQUEST_URL',
    ]:
        url = urllib.parse.urlsplit(os.environ.get(name, ''))
        host = url.hostname or ''
        if url.scheme == 'https' and host.endswith(('.actions.githubusercontent.com', '.githubapp.com')):
            hosts.add(host + ':443')
    for host in [
        'codeload.github.com',
        'results-receiver.actions.githubusercontent.com',
        'actions-results-receiver-production.githubapp.com',
        'pipelines.actions.githubusercontent.com',
    ]:
        hosts.add(host + ':443')
    # The runner fetches nested actions after checkout. Codeload DNS rotates within GitHub's
    # Git service ranges, so resolving it once can strand subsequent runner archive requests.
    git_networks = [ipaddress.ip_network(cidr) for cidr in metadata['git']]
    if not git_networks or any(not network.is_global or network.prefixlen == 0 for network in git_networks):
        raise RuntimeError('Invalid GitHub Git service network ranges')
    return hosts, git_networks


def verify_direct_denial():
    with socket.create_connection(('api.github.com', 443), timeout=5):
        pass
    for address in ('1.1.1.1', '2606:4700:4700::1111'):
        try:
            with socket.create_connection((address, 443), timeout=2):
                pass
        except OSError:
            continue
        raise RuntimeError(f'Hosted macOS can reach undeclared endpoint {address}')


def install():
    if sys.platform != 'darwin' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise RuntimeError('PF policy installation requires a disposable macOS hosted runner')
    sudo = ['sudo', '-n', PF]
    rules = command(sudo + ['-sr'], capture_output=True).stdout
    if not re.search(r'(?m)^anchor "com\.apple/\*"', rules):
        raise RuntimeError('The system PF configuration does not invoke com.apple child anchors')
    endpoints, git_networks = configured_hosts()
    tables = {80: set(), 443: set()}
    targets = [endpoint.rsplit(':', 1) for endpoint in sorted(endpoints)]
    with ThreadPoolExecutor(max_workers=8) as lookups:
        for (_, port), resolved in zip(targets, lookups.map(addresses, (host for host, _ in targets))):
            tables[int(port)].update(resolved)
    dns = command(['/usr/sbin/scutil', '--dns'], capture_output=True).stdout
    resolvers = {
        str(ipaddress.ip_address(value.split('%')[0]))
        for value in re.findall(r'nameserver\[\d+\]\s*:\s*(\S+)', dns)
    }
    policy = ['anchor "gradle-ipc" quick on lo0', 'pass quick on lo0 all']
    runner_uid = os.getuid()
    if runner_uid in (0, GUEST_UID):
        raise RuntimeError('Expected a distinct unprivileged runner account')
    policy.insert(0, 'table <migrations_git> persist { ' + ', '.join(map(str, git_networks)) + ' }')
    policy.append(f'pass out quick proto tcp to <migrations_git> port 443 user {runner_uid} keep state')
    if resolvers:
        policy.append(
            'pass out quick proto { udp tcp } to { ' + ', '.join(sorted(resolvers)) + ' } port 53 keep state'
        )
    for port, allowed in tables.items():
        if not allowed:
            continue
        policy.insert(0, f'table <migrations{port}> persist {{ ' + ', '.join(sorted(allowed)) + ' }')
        policy.append(f'pass out quick proto tcp to <migrations{port}> port {port} keep state')
    policy.extend(['block drop in quick proto tcp flags S/SA', 'block drop out quick all'])
    text = '\n'.join(policy) + '\n'
    command(sudo + ['-n', '-a', ANCHOR, '-f', '-'], input=text)
    command(sudo + ['-a', ANCHOR, '-f', '-'], input=text)
    command(sudo + ['-E'], capture_output=True)
    verify_direct_denial()
    print('Hosted IPv4 and IPv6 outbound PF policy active')


@contextmanager
def local_ipc():
    if sys.platform != 'darwin' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise RuntimeError('Expected a disposable GitHub macOS worker')
    first, last = [int(command(['/usr/sbin/sysctl', '-n', 'net.inet.ip.portrange.' + key],
                               capture_output=True).stdout) for key in ('first', 'last')]
    if not (1024 <= first < last <= 65535):
        raise RuntimeError('Unexpected ephemeral TCP port range')
    ports = range(first, last + 1)
    anchor = ANCHOR + '/gradle-ipc'
    existing = subprocess.run(['/usr/bin/pgrep', '-u', str(GUEST_UID)], capture_output=True)
    if existing.returncode != 1:
        raise RuntimeError('Expected an unused non-administrative UID for the worker')
    broker_port = urllib.parse.urlsplit(os.environ.get('CI_DEPENDENCY_PROXY_URL', '')).port or 30071
    # Classify candidate connections on output, then check the receiving listener's UID on input.
    # Runner and broker connections use the VM's ordinary allocator and never receive this tag.
    rules = (f'pass out quick on lo0 inet proto tcp to 127.0.0.1 port {broker_port} user {GUEST_UID} keep state\n'
             f'pass out quick on lo0 inet proto tcp to 127.0.0.1 port {first}:{last} user {GUEST_UID} '
             'no state tag MIGRATIONS_GUEST\n'
             f'block return-rst out quick on lo0 proto tcp user {GUEST_UID}\n'
             f'block return-rst in quick on lo0 inet proto tcp to 127.0.0.1 user != {GUEST_UID} '
             'tagged MIGRATIONS_GUEST\n')
    try:
        command(['/usr/bin/sudo', '-n', PF, '-a', anchor, '-f', '-'], input=rules)
        yield ports
    finally:
        command(['/usr/bin/sudo', '-n', PF, '-a', anchor, '-F', 'rules'], capture_output=True)


def seatbelt(temp, java, port, ipc):
    def quote(value):
        return json.dumps(str(value))

    read = [f'(subpath {quote(path)})' for path in OS_READ + [str(java), str(temp)]]
    # Only the fresh working directory and two device files are writable.
    return '\n'.join(
        [
            '(version 1)',
            # Keep platform launch/runtime operations usable, then restrict each capability that crosses
            # the worker boundary. Recent dyld/libSystem abort at process start with blanket default deny.
            '(allow default)',
            '(deny file-read* file-write* file-map-executable)',
            '(allow file-read* file-map-executable ' + ' '.join(read) + ')',
            '(allow file-read* (literal "/private/var/select/sh"))',
            # macOS dyld reads the root directory itself when starting every executable. A literal
            # filter grants access to that one directory entry while preserving child path restrictions.
            '(allow file-read-data (literal "/"))',
            '(allow file-read-metadata)',
            f'(allow file-write* (subpath {quote(temp)}) (literal "/dev/null") (literal "/dev/tty"))',
            '(deny file-write-setugid)',
            '(deny process-info* signal mach-lookup)',
            '(allow process-info* signal (target same-sandbox))',
            '(allow mach-lookup (global-name "com.apple.system.opendirectoryd.libinfo"))',
            '(allow mach-lookup (global-name "com.apple.SystemConfiguration.DNSConfiguration"))',
            '(deny network*)',
            f'(allow network-outbound (remote tcp "localhost:{port}"))',
            # Darwin checks bind before it has assigned the ephemeral port. Inbound/listen and
            # outbound are restricted to loopback, with receiver ownership enforced by PF.
            '(allow network-bind (local tcp "localhost:*"))',
            '(allow network-inbound (local tcp "localhost:*"))',
            '(allow network-outbound (remote tcp "localhost:*"))',
            # Gradle 8 requests an ephemeral UDP port for its file-lock service. Seatbelt expresses
            # the resulting unknown port as '*'. Sending and receiving remain separate operations.
            '(allow network-bind (local udp "*:*"))',
            '(deny process-exec (literal "/usr/bin/sudo") (literal "/usr/bin/su"))',
        ]
    )


@contextmanager
def guest_directory(prefix):
    directory = tempfile.mkdtemp(prefix=prefix, dir='/private/tmp')
    try:
        yield directory
    finally:
        command(['/usr/bin/sudo', '-n', '/bin/rm', '-rf', '--', directory])


def build(ipc, command_line=None):
    command_line = command_line or ['build']
    if sys.platform != 'darwin' or not shutil.which('sandbox-exec'):
        raise RuntimeError('Hosted macOS Seatbelt is required')
    proxy = urllib.parse.urlsplit(os.environ['CI_DEPENDENCY_PROXY_URL'])
    if not (proxy.scheme == 'http' and proxy.hostname == '127.0.0.1' and proxy.port and proxy.path in ('', '/')):
        raise RuntimeError('Expected one loopback dependency broker')
    source = Path(os.environ['CI_DEPENDENCY_TEST_SOURCE']).resolve(strict=True)
    java = Path(os.environ['JAVA_HOME']).resolve(strict=True)
    with guest_directory('migrations-seatbelt-') as directory:
        temp = Path(directory).resolve()
        work = temp / 'candidate'
        work.mkdir()
        archive = temp / 'source.tar'
        command(['/usr/bin/git', '-C', str(source), 'archive', '-o', str(archive), 'HEAD'])
        command(['/usr/bin/tar', '-xf', str(archive), '-C', str(work)])
        archive.unlink()
        home = temp / 'home'
        home.mkdir()
        tmp = temp / 'tmp'
        tmp.mkdir()
        wrapper = temp / 'wrapper'
        wrapper.mkdir()
        for suffix in ['.jar', '.properties']:
            file = Path(os.environ['CI_DEPENDENCY_GRADLE_WRAPPER_JAR']).with_suffix(suffix)
            shutil.copy(file, wrapper / file.name)
        profile = temp / 'candidate.sb'
        # setup-java normally installs under the runner's private home. Copy the verified JDK
        # into the job workspace before dropping to the separate non-administrative UID.
        guest_java = temp / 'jdk'
        command(['/usr/bin/ditto', str(java), str(guest_java)])
        java = guest_java
        profile.write_text(seatbelt(temp, java, proxy.port, ipc))
        command(['/usr/bin/sudo', '-n', '/usr/sbin/chown', '-R', str(GUEST_UID), str(profile.parent)])
        command(['/usr/bin/sudo', '-n', '/bin/chmod', '0755', str(temp)])
        prefix = ['/usr/bin/sudo', '-n', '/usr/bin/python3', '-I', str(Path(__file__).resolve()), 'guest']
        env = {
            'PATH': f'{java}/bin:/usr/bin:/bin:/usr/sbin:/sbin',
            'HOME': str(home),
            'TMPDIR': str(tmp),
            'JAVA_HOME': str(java),
            # Seatbelt checks the destination address family. Match the local broker's real IPv4 socket
            # instead of using Java's IPv4-mapped IPv6 default for a literal IPv4 endpoint.
            'JAVA_TOOL_OPTIONS': '-Djava.net.preferIPv4Stack=true',
            'JAVA_OPTS': '-Xms64m -Xmx1024m',
            'CI': 'true',
            'GRADLE_USER_HOME': str(home / 'gradle'),
            'MAVEN_REPOSITORY_URL': f'{proxy.geturl().rstrip("/")}/maven/',
            'CI_DEPENDENCY_PROXY_URL': proxy.geturl(),
            'CI_DEPENDENCY_GRADLE_WRAPPER_JAR': str(wrapper / 'gradle-wrapper.jar'),
            'OS_MIGRATIONS_GRADLE_SCAN_TOS_AGREE_AND_ENABLED': '',
        }
        if 'CI_GRADLE_BUILD_CACHE_URL' in os.environ:
            env['CI_GRADLE_BUILD_CACHE_URL'] = f'{proxy.geturl().rstrip("/")}/gradle-build-cache/'
            env['CI_GRADLE_BUILD_CACHE_PUSH'] = 'false'
        prefix += [json.dumps(env), str(profile)]
        command(prefix + ['/usr/bin/nc', '-z', '-G', '3', '127.0.0.1', str(proxy.port)], env=env, cwd=work)
        for target in ('1.1.1.1', '2606:4700:4700::1111'):
            result = subprocess.run(prefix + ['/usr/bin/nc', '-z', '-G', '2', target, '443'], env=env, cwd=work)
            if result.returncode == 0:
                raise RuntimeError('Seatbelt allowed direct external traffic')
        # Match Gradle's client/daemon JVM arguments so --no-daemon can execute entirely inside one JVM.
        try:
            command(prefix + [str(work / 'gradlew'), '--no-daemon', '-Dorg.gradle.jvmargs=-Xms64m -Xmx1024m',
                              '-Dorg.gradle.internal.instrumentation.agent=false', *command_line], env=env, cwd=work)
        except subprocess.CalledProcessError:
            predicate = ('subsystem == "com.apple.sandbox.reporting" AND '
                         '(eventMessage CONTAINS "Sandbox: java(" OR eventMessage CONTAINS "Sandbox: sh(")')
            logs = subprocess.run(['/usr/bin/log', 'show', '--style', 'compact', '--last', '2m', '--predicate',
                                   predicate], capture_output=True, text=True, timeout=15)
            denials = [line[:420] for line in logs.stdout.splitlines() if 'deny(' in line or 'deny ' in line]
            print('Java sandbox diagnostics:', '\n'.join(denials[-12:]), flush=True)
            raise
        if command_line != ['build']:
            command(['/usr/bin/sudo', '-n', '/usr/sbin/chown', '-R', str(os.getuid()), str(temp)])
            result = work / '.github/ci/network/gradle-fixture/build/classes/java/main/network/Probe.class'
            if not result.is_file() or result.read_bytes()[:4] != bytes.fromhex('cafebabe'):
                raise RuntimeError('Expected a compiled Java class from the clean Seatbelt build')
            print('Compiled a Java class using the repository wrapper, Corretto and brokered JUnit dependencies.')


def fixture(ipc):
    if sys.platform != 'darwin':
        raise RuntimeError('Run this proof on macOS')

    class Marker(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'local broker')

    with http.server.HTTPServer(('127.0.0.1', 30071), Marker) as server, socket.socket() as private_tcp, \
            guest_directory('migrations-ipc-proof-') as staged, \
            tempfile.NamedTemporaryFile(dir='/private/tmp') as private:
        for candidate in range(29000, 29500):
            try:
                private_tcp.bind(('127.0.0.1', candidate))
                break
            except OSError:
                continue
        else:
            raise RuntimeError('Could not reserve the second host listener outside the Gradle IPC range')
        private_tcp.listen()
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        temporary = Path(staged).resolve()
        profile = temporary / 'candidate.sb'
        profile.write_text(seatbelt(temporary, Path('/Library/Java'), port, ipc))
        command(['/bin/chmod', '0755', str(temporary)])
        command(['/usr/bin/sudo', '-n', '/usr/sbin/chown', str(GUEST_UID), str(temporary)])
        prefix = ['/usr/bin/sudo', '-n', '/usr/bin/python3', '-I', str(Path(__file__).resolve()), 'guest']
        env = {'HOME': str(temporary), 'PATH': '/usr/bin:/bin', 'TMPDIR': str(temporary)}

        prefix += [json.dumps(env), str(profile)]

        def execute(argv):
            return subprocess.run(
                prefix + argv, env=env, cwd=temporary, capture_output=True, text=True, timeout=15
            )

        baseline = execute(['/usr/bin/true'])
        if baseline.returncode:
            # Select this probe's diagnostics. The macOS log is otherwise dominated by unrelated system apps.
            predicate = '(eventMessage CONTAINS "Sandbox: true(" OR process == "true")'
            logs = subprocess.run(['/usr/bin/log', 'show', '--style', 'compact', '--last', '1m', '--predicate',
                                   predicate], capture_output=True, text=True, timeout=15)
            print('Native process diagnostics:', logs.stdout[-2500:], flush=True)
            raise RuntimeError(f'Seatbelt native exec failed ({baseline.returncode}): {baseline.stderr}')
        # Bind a runner-owned service inside the allowed range AFTER the empty-port preflight.
        # Seatbelt permits this port. PF must deny it based on the receiving socket's ownership.
        with socket.socket() as host_after:
            host_after.bind(('127.0.0.1', 0))
            host_after.listen()
            if execute(['/usr/bin/nc', '-z', '-G', '2', '127.0.0.1', str(host_after.getsockname()[1])]).returncode == 0:
                raise RuntimeError('Candidate connected to a later host-owned listener inside its IPC range')
        response = execute(['/usr/bin/curl', '-fsS', '--max-time', '5', f'http://127.0.0.1:{port}/fixture'])
        if response.returncode or response.stdout != 'local broker':
            raise RuntimeError(f'Candidate could not reach its broker ({response.returncode}): {response.stderr}')
        with socket.socket(type=socket.SOCK_DGRAM) as host_udp:
            host_udp.bind(('127.0.0.1', 0))
            host_udp.settimeout(0.5)
            # /usr/bin/python3 launches Xcode on macOS. Compile this fixed trusted probe outside
            # Seatbelt and run its small system-libc binary inside the same profile as the candidate.
            binary = temporary / 'udp'
            fd, filename = tempfile.mkstemp(prefix='migrations-udp-', suffix='.c', dir='/private/tmp')
            os.close(fd)
            source = Path(filename)
            source.write_text(r'''#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
int main(int argc, char **argv) {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in addr = {.sin_family=AF_INET};
    if (argc != 2 || sock < 0 || bind(sock, (struct sockaddr *)&addr, sizeof(addr))) return 1;
    puts("udp-bound"); fflush(stdout);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); addr.sin_port = htons(atoi(argv[1]));
    (void)sendto(sock, "candidate", 9, 0, (struct sockaddr *)&addr, sizeof(addr));
    return 0;
}
''')
            try:
                command(['/usr/bin/xcrun', 'clang', '-Wall', '-Werror', str(source), '-o', str(source) + '.out'])
                command(['/usr/bin/sudo', '-n', '/bin/mv', str(source) + '.out', str(binary)])
            finally:
                source.unlink()
            udp = execute([str(binary), str(host_udp.getsockname()[1])])
            if udp.returncode or 'udp-bound' not in udp.stdout:
                raise RuntimeError(f'Candidate UDP bind failed ({udp.returncode}): ' + udp.stderr[-350:])
            try:
                host_udp.recv(128)
                raise RuntimeError('Candidate sent a datagram to a private host service')
            except socket.timeout:
                pass
        github = next(iter(addresses('api.github.com')))
        for argv in [
            ['/usr/bin/nc', '-z', '-G', '2', github, '443'],
            ['/usr/bin/nc', '-z', '-G', '2', '127.0.0.1', str(private_tcp.getsockname()[1])],
            ['/bin/cat', private.name],
            ['/usr/bin/sudo', '-n', 'true'],
        ]:
            if execute(argv).returncode == 0:
                raise RuntimeError(f'Candidate isolation did not deny {argv[0]}')
        if execute(['/usr/bin/touch', str(temporary / 'built')]).returncode:
            raise RuntimeError('Candidate cannot write a build output')
        print(
            'Hosted PF and Seatbelt passed. The worker reached its broker and created output. '
            'Seatbelt denied the host control plane, private files, and sudo.'
        )


if __name__ == '__main__':
    if sys.argv[1:2] == ['guest']:
        # An unmapped UID has no password, admin groups or home service. sudo -u only accepts
        # Directory Services accounts on hosted macOS, so the trusted parent drops it directly.
        os.setgroups([])
        os.setgid(65534)
        os.setuid(GUEST_UID)
        os.execve('/usr/bin/sandbox-exec', ['sandbox-exec', '-f', sys.argv[3], *sys.argv[4:]], json.loads(sys.argv[2]))
    elif sys.argv[1:] == ['install']:
        install()
    else:
        with local_ipc() as ipc:
            if sys.argv[1:] == ['gradle-fixture']:
                build(ipc, ['--project-dir', '.github/ci/network/gradle-fixture', 'build', '--info', '--stacktrace'])
            else:
                {'build': build, 'fixture': fixture}[sys.argv[1]](ipc)
