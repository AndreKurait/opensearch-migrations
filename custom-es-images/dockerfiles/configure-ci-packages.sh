#!/bin/sh
set -eu
[ -n "${CI_PACKAGE_ENDPOINT:-}" ] || exit 0
if [ -f /etc/alpine-release ]; then
    mkdir -p /tmp/ci-apk
    sed "s|https\?://dl-cdn.alpinelinux.org/alpine/|http://$CI_PACKAGE_ENDPOINT/raw/alpine/|g" /etc/apk/repositories > /tmp/ci-apk/repositories
else
    release=$(cat /etc/dnf/vars/releasever 2>/dev/null || rpm -q --qf '%{VERSION}' system-release)
    arch=$(uname -m)
    base="http://$CI_PACKAGE_ENDPOINT/raw/al2023"
    mirror=$(curl -fsS "$base/core/mirrors/$release/$arch/mirror.list" | head -n 1)
    case "$mirror" in
        https://cdn.amazonlinux.com/al2023/core/guids/*) ;;
        *) echo 'Unexpected Amazon Linux repository mirror' >&2; exit 1 ;;
    esac
    url="$base/${mirror#https://cdn.amazonlinux.com/al2023/}"
    # Keep the image's signing key and signature verification options in the repo file.
    mkdir -p /tmp/ci-rpm-repos
    awk '/^\[/{core=($0=="[amazonlinux]")} core' /etc/yum.repos.d/amazonlinux.repo |
        sed "s|^mirrorlist=.*|baseurl=$url|" > /tmp/ci-rpm-repos/amazonlinux.repo
    grep -q '^baseurl=' /tmp/ci-rpm-repos/amazonlinux.repo
    # Hosted cache reads are paced across concurrent jobs and verified before serving bytes.
    printf '\ntimeout=120\n' >> /tmp/ci-rpm-repos/amazonlinux.repo
fi
