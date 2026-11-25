"""Read one bounded regular file from a pipe containing the GitHub cache's POSIX tar."""

import os
import sys
import tarfile


BLOCK = 512
MAX_METADATA = 16 * 1024
MAX_TRAILER = 64 * 1024


def reject():
    raise ValueError('Invalid single-file dependency cache archive')


def read_exact(stream, count):
    data = stream.read(count)
    if len(data) != count:
        reject()
    return data


def skip_padding(stream, size):
    if any(read_exact(stream, (-size) % BLOCK)):
        reject()


def pax_fields(data):
    # GNU tar --posix emits atime/ctime/mtime, and path for member names exceeding 100 bytes.
    fields = {}
    while data:
        length, sep, _ = data.partition(b' ')
        if not sep or not length.isdigit() or len(length) > 8:
            reject()
        n = int(length)
        if n <= len(length) + 3 or n > len(data) or data[n - 1:n] != b'\n':
            reject()
        key, equal, value = data[len(length) + 1:n - 1].partition(b'=')
        if not equal or key not in {b'path', b'mtime', b'atime', b'ctime', b'uid', b'gid', b'uname', b'gname'} or key in fields:
            reject()
        fields[key] = value
        data = data[n:]
    return fields


def extract(stream, member, output, max_bytes):
    header = tarfile.TarInfo.frombuf(read_exact(stream, BLOCK), 'utf8', 'strict')
    fields = {}
    if header.type == tarfile.XHDTYPE:
        if not 0 < header.size <= MAX_METADATA:
            reject()
        fields = pax_fields(read_exact(stream, header.size))
        skip_padding(stream, header.size)
        header = tarfile.TarInfo.frombuf(read_exact(stream, BLOCK), 'utf8', 'strict')
    name = fields[b'path'].decode('utf8') if b'path' in fields else header.name
    if header.type not in {tarfile.REGTYPE, tarfile.AREGTYPE} or header.linkname or name != member or not 0 <= header.size <= max_bytes:
        reject()
    # The caller creates this file in a newly generated private directory. No member pathname is passed to filesystem operations.
    left = header.size
    with open(output, 'xb') as target:
        while left:
            chunk = read_exact(stream, min(left, 256 * 1024))
            target.write(chunk)
            left -= len(chunk)
    skip_padding(stream, header.size)
    if any(read_exact(stream, 2 * BLOCK)):
        reject()
    trailer = stream.read(MAX_TRAILER + 1)
    if len(trailer) > MAX_TRAILER or any(trailer):
        reject()


if __name__ == '__main__':
    try:
        file, destination, limit = sys.argv[1:]
        os.umask(0o077)
        extract(sys.stdin.buffer, file, destination, int(limit))
    except (ValueError, OSError, tarfile.TarError, UnicodeError):
        sys.exit('Invalid single-file dependency cache archive')
