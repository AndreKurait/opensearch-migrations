#!/usr/bin/env bash
set -euo pipefail

# This launcher and its commands come from the base checkout. The guest builds the candidate in /work/source.
launcher="$(dirname -- "$0")/hermetic-run.sh"
# The hosted Ubuntu 24.04 image includes skopeo and the runtime libraries for bubblewrap.
if ! command -v bwrap >/dev/null && [[ -n "${CI_DEPENDENCY_PROXY_URL:-}" && $(dpkg --print-architecture) == amd64 ]]; then
  package=bubblewrap_0.9.0-1ubuntu0.3_amd64.deb
  archive=$(mktemp)
  trap 'rm -f -- "$archive"' EXIT
  curl --fail --silent --show-error "$CI_DEPENDENCY_PROXY_URL/raw/bubblewrap/$package" -o "$archive"
  echo "2461f1beee9cb04c8942739fe1a2b37e7b7c2a3d518f0779dc75f9245baa3094  $archive" | sha256sum --check --status
  sudo -n dpkg --install "$archive"
  rm -f -- "$archive"
  trap - EXIT
fi
if ! command -v bwrap >/dev/null || ! command -v skopeo >/dev/null; then
  if [[ ${CI_DEPENDENCY_STRICT_READ:-0} == 1 ]]; then
    echo 'The cache-only runner requires bubblewrap and skopeo' >&2
    exit 1
  fi
  bash "$(dirname -- "$0")/install-ubuntu-tools.sh" bubblewrap skopeo
fi
suite=${1:?Missing repository check}
case "$suite" in
  gradle-extended)
    case "${2:-}" in spotlessCheck|publishToMavenLocal) ;; *) echo 'Unknown extended Gradle task' >&2; exit 2;; esac
    exec "$launcher" ./gradlew "$2"
    ;;
  gradle-repositories)
    exec "$launcher" python3 -m unittest discover -s gradle/tests -v
    ;;
  python-lint)
    exec "$launcher" bash -euc '
      # /source contains just the files tracked in the candidate Git tree. /work/source may already contain generated files.
      python3 -m venv /tmp/lint
      /tmp/lint/bin/python -m pip install --upgrade pip
      /tmp/lint/bin/python -m pip install flake8
      cd /source
      find . -type f -name "*.py" -print0 | xargs -0 -r /tmp/lint/bin/flake8
    '
    ;;
  python-tests|node-tests)
    : "${CI_HERMETIC_OUTPUT_DIR:?Missing coverage output directory}"
    if [[ "$suite" == 'python-tests' ]]; then
      report=coverage.xml
      "$launcher" bash -euc '
        python3 -m venv /tmp/pipenv
        /tmp/pipenv/bin/python -m pip install pipenv==2026.5.0
        export PATH="/tmp/pipenv/bin:$PATH" PIPENV_IGNORE_VIRTUALENVS=1
        pipenv install --deploy --dev
        pipenv graph
        pipenv run test
        pipenv run coverage xml -o /export/coverage.xml
      '
    else
      report=lcov.info
      "$launcher" bash -euc '
        npm ci
        npm run test
        cp -- coverage/lcov.info /export/lcov.info
      '
    fi
    # The host uploader opens one bounded ordinary file under the job temp directory. Never scan candidate output for uploadable paths.
    python3 - "$CI_HERMETIC_OUTPUT_DIR" "$report" <<'PY'
import os
import stat
import sys
from pathlib import Path

directory, expected = Path(sys.argv[1]), sys.argv[2]
if set(os.listdir(directory)) != {expected}:
    sys.exit("Unexpected contents in isolated coverage output")
file_stat = (directory / expected).lstat()
if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink != 1 or not 0 < file_stat.st_size <= 32 * 1024**2:
    sys.exit("Invalid isolated coverage report")
PY
    ;;
  *) echo 'Unsupported isolated repository check' >&2; exit 2 ;;
esac
