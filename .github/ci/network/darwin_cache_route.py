"""Refresh only the protected GitHub cache connection the trusted broker is about to open."""
import ipaddress
import os
import re
import socket
import subprocess
import sys


def authorize(phase, host):
    if sys.platform != 'darwin' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise ValueError('Expected a disposable hosted macOS worker')
    # Results comes from the Actions JS runtime. Storage comes exclusively from its authenticated
    # GetCacheEntryDownloadURL response. The candidate cannot provide either value or invoke sudo.
    patterns = {
        'metadata': r'(?:[a-z0-9-]+\.)+(?:actions\.githubusercontent\.com|githubapp\.com)',
        'archive': r'[a-z0-9]+\.blob\.core\.windows\.net',
    }
    if not re.fullmatch(patterns[phase], host):
        raise ValueError(f'Unexpected GitHub cache {phase} hostname {host}')
    addresses = sorted({row[4][0] for row in socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
                        if ipaddress.ip_address(row[4][0]).is_global},
                       key=lambda value: ipaddress.ip_address(value).version)
    if not addresses:
        raise ValueError(f'No public address for GitHub cache {phase} hostname {host}')
    subprocess.run(['/usr/bin/sudo', '-n', '/sbin/pfctl', '-a', 'com.apple/migrations-egress', '-t',
                    'migrations443', '-T', 'add', *addresses], check=True, stdout=subprocess.DEVNULL)
    print('\n'.join(addresses))


if __name__ == '__main__':
    authorize(*sys.argv[1:])
