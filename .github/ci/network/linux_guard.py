#!/usr/bin/env python3
"""Bind the Community agent's permitted IP/port pairs to every hosted network interface."""
import argparse
import ipaddress
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import time

CHAIN = 'MIGRATIONS_EGRESS'
CONTROL = Path('/var/lib/migrations-egress')
DNS = ('8.8.8.8', '8.8.4.4', '1.1.1.1')


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def allowed_pairs(rules):
    pairs = set()
    for line in rules.splitlines():
        words = shlex.split(line)
        if words[:2] != ['-A', 'OUTPUT'] or '-o' not in words or words[words.index('-o') + 1] != 'eth0':
            continue
        if not all(field in words for field in ('-d', '-p', '--dport', '-j')) or '--uid-owner' in words:
            continue
        if words[words.index('-j') + 1] != 'ACCEPT' or words[words.index('-p') + 1] != 'tcp':
            continue
        address = ipaddress.ip_network(words[words.index('-d') + 1], strict=True)
        port = words[words.index('--dport') + 1]
        if address.prefixlen == 32 and address.network_address.is_global and port in {'80', '443'}:
            pairs.add((str(address.network_address), port))
    return sorted(pairs)


def reconcile(pairs, ipv6=False):
    prefix = 'ip6tables' if ipv6 else 'iptables'
    cmd = [prefix, '-w', '5']
    if subprocess.run(cmd + ['-S', CHAIN], capture_output=True).returncode:
        run(cmd + ['-N', CHAIN])
    records = ['*filter', f'-F {CHAIN}']
    allow = lambda body: records.append(f'-A {CHAIN} {body} -j ACCEPT')
    # Only in-VM networks are reachable unrestricted. Private addresses on the cloud NIC stay blocked.
    for interface in ('lo', 'docker0', 'br+', 'cni+', 'veth+'):
        allow(f'-o {interface}')
    allow('-m conntrack --ctstate ESTABLISHED,RELATED')
    if not ipv6:
        # Only the vendor DNS agent (root on the hosted images) needs upstream DoH.
        # An owner match is valid only on OUTPUT. Forwarded workloads cannot use these exceptions.
        for address, port in pairs:
            allow(f'-d {address}/32 -p tcp --dport {port}')
    records += [f'-A {CHAIN} -j DROP', 'COMMIT']
    run([prefix + '-restore', '--wait', '5', '--noflush'], input='\n'.join(records) + '\n')
    for hook in ('OUTPUT', 'FORWARD'):
        jump = ['-j', CHAIN]
        if subprocess.run(cmd + ['-C', hook, *jump], capture_output=True).returncode:
            run(cmd + ['-I', hook, '1', *jump])
    if not ipv6:
        for address in DNS:
            rule = ['-m', 'owner', '--uid-owner', '0', '-d', address, '-p', 'tcp', '--dport', '443', '-j', 'ACCEPT']
            if subprocess.run(cmd + ['-C', 'OUTPUT', *rule], capture_output=True).returncode:
                run(cmd + ['-I', 'OUTPUT', '1', *rule])


def vendor_pairs():
    rules = run(['iptables', '-w', '5', '-S', 'OUTPUT'], capture_output=True).stdout
    pairs = allowed_pairs(rules)
    if not pairs or not re.search(r'^-A OUTPUT -o eth0 (?:-p all )?-j REJECT', rules, re.M):
        raise RuntimeError('The pinned StepSecurity Community block-policy rules are missing')
    return pairs


def sync():
    previous = None
    while True:
        pairs = vendor_pairs()
        if pairs != previous:
            reconcile(pairs)
            previous = pairs
        time.sleep(0.25)


def install():
    if os.getuid() or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise RuntimeError('Install requires a disposable GitHub hosted Linux runner')
    CONTROL.mkdir(exist_ok=True, mode=0o755)
    script = CONTROL / 'linux_guard.py'
    shutil.copyfile(__file__, script)
    os.chmod(script, 0o555)
    reconcile(vendor_pairs())
    reconcile([], ipv6=True)
    run(['systemd-run', '--unit=migrations-egress-sync', '--property=Restart=on-failure',
         '/usr/bin/python3', str(script), 'sync'])
    run(['systemctl', 'is-active', '--quiet', 'migrations-egress-sync.service'])


def probe():
    if os.geteuid() == 0:
        raise RuntimeError('Network probe must run as the worker user')
    for family, target in [(socket.AF_INET, ('1.1.1.1', 443)), (socket.AF_INET6, ('2606:4700:4700::1111', 443, 0, 0))]:
        try:
            with socket.socket(family) as sock:
                sock.settimeout(2)
                sock.connect(target)
        except OSError:
            continue
        raise RuntimeError(f'Runner can reach undeclared endpoint {target[0]}')
    # An allowlisted control-plane endpoint must still be reachable. A dead network cannot pass the fixture.
    with socket.create_connection(('api.github.com', 443), timeout=7):
        pass
    print('Fresh external IPv4/IPv6 TCP denied and GitHub control-plane TCP reachable')


def lock_down():
    import pwd
    name = os.environ['SUDO_USER']
    if name != 'runner' or pwd.getpwnam(name).pw_uid == 0:
        raise RuntimeError('Unexpected GitHub-hosted runner account')
    run(['systemctl', 'stop', 'docker.socket', 'docker.service', 'containerd.service'])
    for file in ('/run/docker.sock', '/run/containerd/containerd.sock'):
        if Path(file).exists():
            os.chmod(file, 0o600)
    fragment = Path('/etc/sudoers.d/zz-migrations-job')
    fragment.write_text('runner ALL=(ALL:ALL) !ALL\n')
    os.chmod(fragment, 0o440)
    run(['visudo', '-c'], stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    options = argparse.ArgumentParser()
    options.add_argument('command', choices=('install', 'sync', 'probe', 'lock-down'))
    args = options.parse_args()
    {'install': install, 'sync': sync, 'probe': probe, 'lock-down': lock_down}[args.command]()
