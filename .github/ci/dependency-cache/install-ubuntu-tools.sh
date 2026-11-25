#!/usr/bin/env bash
set -euo pipefail

# The disposable Ubuntu image normally has a signed package index. Refresh it only when installation needs it.
options=(-o Acquire::Retries=1 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15)
install_tools() { sudo -n apt-get "${options[@]}" -qq --no-install-recommends install -y "$@"; }
if ! install_tools "$@"; then
  sudo -n apt-get "${options[@]}" -qq update
  install_tools "$@"
fi
