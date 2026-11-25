import hashlib
import importlib.util
from pathlib import Path
import socket
import tempfile
import unittest

MODULE = Path(__file__).with_name("worker.py")
spec = importlib.util.spec_from_file_location("container_worker", MODULE)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class IsolationOptions(unittest.TestCase):
    def test_candidate_network_and_cpu_require_hardware_acceleration(self):
        with tempfile.TemporaryDirectory() as tmp, socket.socket(socket.AF_UNIX) as s:
            endpoint = str(Path(tmp) / "cache")
            s.bind(endpoint)
            network = worker.guest_network(1234, endpoint)
            self.assertIn(
                "restrict=y,guestfwd=tcp:10.0.2.100:7777-cmd:socat STDIO UNIX-CONNECT:cache.sock", network
            )
            self.assertIn("hostfwd=tcp:127.0.0.1:1234-:22", network)
            args = worker.qemu_command(network)
            self.assertIn("q35,accel=kvm", args)
            self.assertIn("-nodefaults", args)
            self.assertNotIn("-virtfs", args)
            self.assertNotIn("tcg", " ".join(args))
            self.assertNotIn(endpoint, " ".join(args))

    def test_trusted_preparation_has_no_job_cache_and_wrong_digests_fail_before_any_image_tools(self):
        self.assertEqual(worker.guest_network(4321), "user,id=net,hostfwd=tcp:127.0.0.1:4321-:22")
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "image"
            image.write_bytes(b"safe test bytes")
            with self.assertRaisesRegex(RuntimeError, "digest"):
                worker.verify_image(image, hashlib.sha256(b"different").hexdigest())


if __name__ == "__main__":
    unittest.main()
