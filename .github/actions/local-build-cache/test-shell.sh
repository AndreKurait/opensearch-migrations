#!/usr/bin/env bash
set -euo pipefail

# Keep the cache broker, runner, and artifact uploader in their existing cgroups.
# Child processes (including Gradle daemons) inherit the test shell's cgroup.
if [[ "${CI_DEPENDENCY_STRICT_READ:-0}" == 1 ]]; then
  echo "$$" | sudo tee /sys/fs/cgroup/migrations-local-tests/cgroup.procs >/dev/null
fi
exec bash --noprofile --norc -eo pipefail "$@"
