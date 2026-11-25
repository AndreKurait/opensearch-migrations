"""Guard regressions. Run the packet fixture with sudo unshare --net and no host firewall writes."""

import os
import subprocess
import unittest
from unittest.mock import patch

import linux_guard


class Policy(unittest.TestCase):
    def test_initialization_waits_for_both_the_allowlist_and_reject_rule(self):
        allow = '-A OUTPUT -o eth0 -d 93.184.215.14/32 -p tcp --dport 443 -j ACCEPT'
        reject = '-A OUTPUT -o eth0 -j REJECT --reject-with icmp-port-unreachable'
        states = ('', allow, reject, allow + '\n' + reject)
        output = [subprocess.CompletedProcess([], 0, stdout=text) for text in states]
        with patch.object(linux_guard, 'run', side_effect=output), patch.object(linux_guard.time, 'sleep') as sleep:
            self.assertEqual(linux_guard.vendor_pairs(timeout=90), [('93.184.215.14', '443')])
        self.assertEqual(sleep.call_count, 3)

    def test_initialization_deadline_fails_closed(self):
        with patch.object(linux_guard, 'run', return_value=subprocess.CompletedProcess([], 0, stdout='')), \
                patch.object(linux_guard.time, 'monotonic', side_effect=[0, 90]), \
                patch.object(linux_guard.time, 'sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError, 'block-policy rules are missing'):
                linux_guard.vendor_pairs(timeout=90)
            sleep.assert_not_called()

    def test_only_agent_permitted_public_pairs_are_copied(self):
        rules = '''-A OUTPUT -d 8.8.8.8/32 -o eth0 -p tcp -m owner --uid-owner 0 --dport 443 -j ACCEPT
-A OUTPUT -o eth0 -d 93.184.215.14/32 -p tcp --dport 443 -j ACCEPT
-A OUTPUT -o eth0 -d 10.0.0.0/8 -j ACCEPT
-A OUTPUT -d 168.63.129.16/32 -o eth0 -p tcp -j ACCEPT
-A OUTPUT -o eth0 -d 169.254.169.254/32 -p tcp -j ACCEPT
-A OUTPUT -o eth0 -d 127.0.0.1/32 -p tcp --dport 443 -j ACCEPT
-A OUTPUT -o eno1 -d 8.8.4.4/32 -p tcp --dport 443 -j ACCEPT'''
        self.assertEqual(linux_guard.allowed_pairs(rules), [('93.184.215.14', '443')])

    @unittest.skipUnless(
        os.environ.get('TEST_PRIVATE_NETNS') == '1', 'requires a private Linux network namespace'
    )
    def test_real_output_and_forward_on_non_eth0_interface(self):
        if os.getuid() or os.stat('/proc/self/ns/net').st_ino == os.stat('/proc/1/ns/net').st_ino:
            self.fail('Run this fixture in an isolated root network namespace')

        def run(args):
            return subprocess.run(args, check=True, capture_output=True, text=True)

        def newns():
            proc = subprocess.Popen(['unshare', '--net', 'sleep', '60'])
            self.addCleanup(lambda: (proc.terminate(), proc.wait()))
            import time

            for _ in range(100):
                if os.stat(f'/proc/{proc.pid}/ns/net').st_ino != os.stat('/proc/self/ns/net').st_ino:
                    return ['nsenter', '-t', str(proc.pid), '-n', '--']
                time.sleep(0.01)
            self.fail('Peer network namespace did not start')

        upstream, guest = newns(), newns()
        for host, peer, peer_ns, ip in [
            ('ens5', 'outside', upstream, '198.18.0'),
            ('br-ci', 'inside', guest, '198.18.1'),
        ]:
            run(['ip', 'link', 'add', host, 'type', 'veth', 'peer', 'name', peer])
            run(['ip', 'link', 'set', peer, 'netns', peer_ns[2]])
            run(['ip', 'address', 'add', ip + '.1/24', 'dev', host])
            run(['ip', 'link', 'set', host, 'up'])
            run(peer_ns + ['ip', 'address', 'add', ip + '.2/24', 'dev', peer])
            run(peer_ns + ['ip', 'link', 'set', peer, 'up'])
        run(guest + ['ip', 'route', 'add', 'default', 'via', '198.18.1.1'])
        run(upstream + ['ip', 'route', 'add', '198.18.1.0/24', 'via', '198.18.0.1'])
        run(['sysctl', '-qw', 'net.ipv4.ip_forward=1'])
        upstream_server = '''import socket,threading,time
servers=[]
for port in (8080,8081):
 s=socket.socket();s.bind(('198.18.0.2',port));s.listen();servers.append(s)
print('ready',flush=True)
time.sleep(30)'''
        peer = subprocess.Popen(
            upstream + ['python3', '-u', '-c', upstream_server], stdout=subprocess.PIPE, text=True
        )
        self.addCleanup(lambda: (peer.terminate(), peer.wait(), peer.stdout.close()))
        self.assertEqual(peer.stdout.readline().strip(), 'ready')

        def reachable(ns, port):
            test = f"import socket; s=socket.create_connection(('198.18.0.2',{port}),.45); s.close()"
            return subprocess.run(ns + ['python3', '-c', test], capture_output=True).returncode == 0

        for ns in ([], guest):
            self.assertTrue(reachable(ns, 8080))
            self.assertTrue(reachable(ns, 8081))
        linux_guard.reconcile([('198.18.0.2', '8080')])
        linux_guard.reconcile([], ipv6=True)
        for ns in ([], guest):
            self.assertTrue(reachable(ns, 8080))
            self.assertFalse(reachable(ns, 8081))
        linux_guard.reconcile([('198.18.0.2', '8081')])
        self.assertTrue(reachable(guest, 8081))
        self.assertFalse(reachable(guest, 8080))


if __name__ == '__main__':
    unittest.main()
