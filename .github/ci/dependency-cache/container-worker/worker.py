"""Run native Docker clients and their own daemon in a credential-free guest on a supported KVM runner."""

import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import signal
import socket
import stat
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
DISK = "worker.qcow2"
GUEST_CACHE = "10.0.2.100:7777"


def run(argv, **kwargs):
    return subprocess.run(argv, check=True, **kwargs)


def check_host():
    if os.getuid() == 0 or os.uname().machine != "x86_64":
        raise RuntimeError("Use an unprivileged x86-64 Linux account with read/write access to /dev/kvm")
    if not os.access("/dev/kvm", os.R_OK | os.W_OK):
        raise RuntimeError("KVM acceleration unavailable. Use an ephemeral KVM-capable Linux runner.")
    for tool in ["qemu-system-x86_64", "qemu-img", "cloud-localds", "ssh", "ssh-keygen", "git", "socat"]:
        if not shutil.which(tool):
            raise RuntimeError(f"Install required runner tool {tool}")


def verify_image(image, digest):
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError("Expected a trusted worker image SHA-256")
    with image.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != digest:
            raise RuntimeError("Worker image digest did not match protected configuration")
    # Only accept a standalone qcow2. A linked backing file or external data file could expose host data.
    info = json.loads(
        run(["qemu-img", "info", "--output=json", "-f", "qcow2", image], capture_output=True).stdout
    )
    if info.get("backing-filename") or "data-file" in info.get("format-specific", {}).get("data", {}):
        raise RuntimeError("Worker image must be standalone")


def guest_network(port, cache_socket=None):
    network = f"user,id=net,hostfwd=tcp:127.0.0.1:{port}-:22"
    if cache_socket is None:
        return network  # Only used to prepare the guest without candidate source or CI credentials.
    if not stat.S_ISSOCK(os.stat(cache_socket).st_mode):
        raise ValueError("Missing dependency-cache Unix socket")
    # The stage symlink always has this fixed, shell-safe relative name. Every connection invokes the same fixed relay.
    return network + f",restrict=y,guestfwd=tcp:{GUEST_CACHE}-cmd:socat STDIO UNIX-CONNECT:cache.sock"


def qemu_command(network):
    return [
        "qemu-system-x86_64",
        "-machine",
        "q35,accel=kvm",
        "-cpu",
        "host",
        "-smp",
        "2",
        "-m",
        "4096",
        "-nodefaults",
        "-display",
        "none",
        "-serial",
        "none",
        "-monitor",
        "none",
        "-no-reboot",
        "-drive",
        f"file={DISK},format=qcow2,if=virtio",
        "-drive",
        "file=seed.img,format=raw,if=virtio,readonly=on",
        "-netdev",
        network,
        "-device",
        "virtio-net-pci,netdev=net",
    ]


def seed(directory):
    key = directory / "ssh"
    run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key])
    (directory / "user-data").write_text(
        "#cloud-config\nusers:\n  - name: runner\n    groups: [sudo]\n"
        "    sudo: ALL=(ALL) NOPASSWD:ALL\n    shell: /bin/bash\n    ssh_authorized_keys:\n      - "
        + key.with_suffix(".pub").read_text().strip()
        + "\nssh_pwauth: false\ndisable_root: true\n"
    )
    (directory / "meta-data").write_text(
        f"instance-id: {secrets.token_hex(16)}\nlocal-hostname: container-worker\n"
    )
    run(["cloud-localds", "seed.img", "user-data", "meta-data"], cwd=directory)


def ssh_options(directory, port):
    return [
        "ssh",
        "-F",
        "/dev/null",
        "-p",
        str(port),
        "-i",
        str(directory / "ssh"),
        "-o",
        "IdentitiesOnly=yes",
        "-o",
        "BatchMode=yes",
        "-o",
        "ForwardAgent=no",
        "-o",
        "ClearAllForwardings=yes",
        "-o",
        "ConnectTimeout=2",
        "-o",
        "StrictHostKeyChecking=accept-new",
        "-o",
        f"UserKnownHostsFile={directory}/known_hosts",
        "runner@127.0.0.1",
    ]


@contextlib.contextmanager
def boot(directory, cache_socket):
    seed(directory)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    network = guest_network(port, cache_socket)
    if cache_socket is not None:
        (directory / "cache.sock").symlink_to(cache_socket)
    # QEMU and each spawned cache relay receive a fresh environment. No host mounts, agent forwarding or host daemon transport.
    child = subprocess.Popen(
        qemu_command(network),
        cwd=directory,
        env={"PATH": "/usr/bin:/bin", "HOME": str(directory), "LANG": "C"},
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        start_new_session=True,
    )
    ssh = ssh_options(directory, port)
    try:
        for _ in range(120):
            if child.poll() is not None:
                raise RuntimeError("KVM guest exited before readiness")
            if (
                subprocess.run(ssh + ["true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
                == 0
            ):
                break
            time.sleep(1)
        else:
            raise RuntimeError("KVM guest SSH readiness timed out")
        run(ssh + ["cloud-init status --wait"])
        yield ssh, child
    finally:
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(8)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()


def prepare(base, output):
    from urllib.request import urlopen

    release = "https://cloud-images.ubuntu.com/noble/20261017/noble-server-cloudimg-amd64.img"
    pinned = "04e8f2461a16a44e78a370284471f29616cdd9baf1893df25807b71f985b6c94"
    if output.exists():
        raise ValueError("Use a new worker image path")
    with tempfile.TemporaryDirectory(prefix="docker-guest-prepare-", dir=output.parent) as temp:
        directory = Path(temp)
        disk = directory / DISK
        if base:
            shutil.copyfile(base, disk)
        else:
            with urlopen(release, timeout=90) as response, disk.open("xb") as f:
                shutil.copyfileobj(response, f)
        verify_image(disk, pinned)
        run(["qemu-img", "resize", "-f", "qcow2", disk, "48G"])
        with boot(directory, None) as (ssh, child):
            with (HERE / "prepare.sh").open("rb") as script:
                run(ssh + ["sudo bash -se"], stdin=script)
            # The preparation script schedules shutdown while its original SSH channel is still authenticated.
            child.wait(timeout=120)
        os.link(disk, output)
    with output.open("rb") as f:
        print(hashlib.file_digest(f, "sha256").hexdigest(), flush=True)


def run_candidate(image, digest, source, socket_path, command):
    verify_image(image, digest)
    if not command or not socket_path:
        raise ValueError("Supply CI_DEPENDENCY_PROXY_SOCKET and a guest command")
    with tempfile.TemporaryDirectory(prefix="docker-guest-") as temp:
        directory = Path(temp)
        run(["qemu-img", "create", "-q", "-f", "qcow2", "-F", "qcow2", "-b", image, directory / DISK, "48G"])
        with boot(directory, Path(socket_path).resolve()) as (ssh, _):
            with (HERE / "preflight.sh").open("rb") as script:
                run(ssh + ["bash -se"], stdin=script)
            # The immutable Git tree carries symlinks safely inside the VM and excludes .git, local keys and checkout credentials.
            with subprocess.Popen(
                ["git", "-C", source, "archive", "--format=tar", "HEAD"], stdout=subprocess.PIPE
            ) as archive:
                run(
                    ssh + ["mkdir -p /home/runner/source && tar -xf - -C /home/runner/source"],
                    stdin=archive.stdout,
                )
                archive.stdout.close()
                if archive.wait():
                    raise RuntimeError("Candidate git archive failed")
            env = {
                "MAVEN_REPOSITORY_URL": "http://127.0.0.1:7777/maven/",
                "PIP_INDEX_URL": "http://127.0.0.1:7777/pypi/simple/",
                "npm_config_registry": "http://127.0.0.1:7777/npm/",
                "CI_GRADLE_BUILD_CACHE_URL": "http://127.0.0.1:7777/gradle-build-cache/",
                "CI_GRADLE_BUILD_CACHE_PUSH": "false",
                "CI_DEPENDENCY_PROXY_URL": "http://127.0.0.1:7777",
            }
            if command == ["@smoke"]:
                fixture = os.environ.get("CI_CONTAINER_PROBE_DIGEST", "")
                if len(fixture) != 64 or any(c not in "abcdef0123456789" for c in fixture):
                    raise RuntimeError("Missing trusted probe image digest")
                with (HERE / "smoke.sh").open("rb") as script:
                    return subprocess.run(
                        ssh + ["env CI_CONTAINER_PROBE_DIGEST=" + fixture + " bash -se"], stdin=script
                    ).returncode
            result = subprocess.run(
                ssh
                + [
                    "cd /home/runner/source && exec "
                    + shlex.join(["env", *(f"{key}={value}" for key, value in env.items()), *command])
                ]
            )
            return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    create = actions.add_parser("prepare")
    create.add_argument("--base", type=Path)
    create.add_argument("--output", type=Path, required=True)
    start = actions.add_parser("run")
    start.add_argument("--image", type=Path, required=True)
    start.add_argument("--sha256", required=True)
    start.add_argument("--source", type=Path, required=True)
    start.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    check_host()
    if args.action == "prepare":
        prepare(args.base, args.output.resolve())
        return 0
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    return run_candidate(
        args.image.resolve(),
        args.sha256,
        args.source.resolve(),
        os.environ.get("CI_DEPENDENCY_PROXY_SOCKET"),
        command,
    )


if __name__ == "__main__":
    raise SystemExit(main())
