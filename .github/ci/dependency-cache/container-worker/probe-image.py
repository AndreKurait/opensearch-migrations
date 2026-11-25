"""Prepare a minimal single-platform OCI image from a package-manager-verified static BusyBox executable."""

import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tarfile


def json_bytes(value):
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def build(directory, binary):
    def put(data, media):
        digest = sha(data)
        with (directory / digest).open("xb") as output:
            output.write(data)
        return {"mediaType": media, "digest": "sha256:" + digest, "size": len(data)}

    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as layer:
        entry = tarfile.TarInfo("bin/busybox")
        entry.size = binary.stat().st_size
        if not 0 < entry.size < 8 * 1024 * 1024:
            raise ValueError("Unexpected BusyBox size")
        entry.mode = 0o755
        with binary.open("rb") as f:
            layer.addfile(entry, f)
        for name in ("sh", "sleep", "wget"):
            entry = tarfile.TarInfo("bin/" + name)
            entry.type = tarfile.SYMTYPE
            entry.mode = 0o777
            entry.linkname = "busybox"
            layer.addfile(entry)
    tar = stream.getvalue()
    layer = put(gzip.compress(tar, mtime=0), "application/vnd.oci.image.layer.v1.tar+gzip")
    config = put(
        json_bytes(
            {
                "architecture": "amd64",
                "os": "linux",
                "config": {"Env": ["PATH=/bin"]},
                "rootfs": {"type": "layers", "diff_ids": ["sha256:" + sha(tar)]},
            }
        ),
        "application/vnd.oci.image.config.v1+json",
    )
    kind = "application/vnd.oci.image.manifest.v1+json"
    manifest = put(
        json_bytes({"schemaVersion": 2, "mediaType": kind, "config": config, "layers": [layer]}), kind
    )
    (directory / "manifest.digest").write_text(manifest["digest"][7:] + "\n")


if __name__ == "__main__":
    os.umask(0o077)
    build(Path(sys.argv[1]), Path(sys.argv[2]))
