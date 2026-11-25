#!/usr/bin/env bash
set -euo pipefail
if (($# == 0)); then echo 'Usage: hermetic-run.sh COMMAND [ARGUMENT ...]' >&2; exit 2; fi
exec "$(dirname -- "$0")/offline-smoke.sh" "$@"
