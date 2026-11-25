#!/usr/bin/env python3
"""Prepare and verify the OpenSearch repository layout used by Central Portal."""

import argparse
import hashlib
import re
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path, PurePosixPath


ARTIFACT_TYPES = {'.jar', '.pom', '.module', '.zip', '.war'}


def unpack(archive, destination, version):
    # Older GitHub prereleases used uncompressed tar with the same .tar.gz name.
    with tarfile.open(archive, 'r:*') as tar:
        for member in tar:
            if member.isdir():
                continue
            path = PurePosixPath(member.name)
            if not member.isfile() or not re.fullmatch(r'[a-zA-Z0-9_./+-]+', member.name) or '..' in path.parts:
                raise ValueError(f'Unexpected Maven archive entry {member.name!r}')
            if path.parts[:3] != ('repository', 'org', 'opensearch') or path.suffix not in ARTIFACT_TYPES:
                continue
            if path.parent.name != version or len(path.parts) < 6:
                raise ValueError(f'Expected version {version} in {path}')
            target = destination.joinpath(*path.parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
    poms = sorted(destination.rglob('*.pom'))
    if not poms or not list(destination.rglob('*.jar')):
        raise ValueError('Maven archive requires published POMs and JARs')
    print(f'Prepared {len(poms)} Maven publications')


def bundle(repository, output):
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(repository.rglob('*')):
            if not path.is_file() or path.suffix not in ARTIFACT_TYPES:
                continue
            signature = path.with_name(path.name + '.asc')
            subprocess.run(['gpg', '--batch', '--verify', signature, path], check=True)
            name = path.relative_to(repository).as_posix()
            archive.write(path, name)
            archive.write(signature, name + '.asc')
            for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                with path.open('rb') as stream:
                    digest = hashlib.file_digest(stream, algorithm).hexdigest()
                archive.writestr(name + '.' + algorithm, digest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('unpack')
    prepare.add_argument('archive', type=Path)
    prepare.add_argument('repository', type=Path)
    prepare.add_argument('version')
    package = commands.add_parser('bundle')
    package.add_argument('repository', type=Path)
    package.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.command == 'unpack':
        unpack(args.archive, args.repository, args.version)
    else:
        bundle(args.repository, args.output)
