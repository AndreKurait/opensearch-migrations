#!/usr/bin/env bash
set -euo pipefail
version="${1:?kind version required}"
case "$version" in
  v0.33.0) kind_digest=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d ;;
  v0.31.0) kind_digest=eb244cbafcc157dff60cf68693c14c9a75c4e6e6fedaf9cd71c58117cb93e3fa ;;
  *) exit 1 ;;
esac
cache_dir="$RUNNER_TOOL_CACHE/kind/$version/amd64"
install_tool() {
  local target="$cache_dir/$1/bin/$1" route="$2" digest="$3"
  mkdir -p "$(dirname "$target")"
  if ! echo "$digest  $target" | sha256sum --check --status 2>/dev/null; then
    curl --fail --silent --show-error --retry 3 "$CI_DEPENDENCY_PROXY_URL/$route" -o "$target.tmp"
    echo "$digest  $target.tmp" | sha256sum --check
    chmod 0755 "$target.tmp"
    mv "$target.tmp" "$target"
  fi
}
install_tool kind "raw/kind/$version/kind-linux-amd64" "$kind_digest"
install_tool kubectl raw/kubectl/release/v1.37.1/bin/linux/amd64/kubectl 65691ff77eb6fa44c908b77a1082c9f092c3b9733b5cefabec0d1104890e21a8
