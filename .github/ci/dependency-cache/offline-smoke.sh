#!/usr/bin/env bash
set -euo pipefail

# Run a candidate command with loopback networking and one filesystem socket to the trusted cache broker.
# Invoke this launcher from the trusted checkout. CI_DEPENDENCY_TEST_SOURCE may point to a PR checkout.
# Hosted CI runners normally execute as a non-root service account. Refuse to stage candidate code as UID 0.
(( $(id -u) != 0 )) || { echo 'Run offline smoke as an unprivileged runner account' >&2; exit 1; }
launcher_dir=$(cd -- "$(dirname -- "$0")" && pwd -P)
source_dir=$(realpath -- "${CI_DEPENDENCY_TEST_SOURCE:-$(git -C "$launcher_dir" rev-parse --show-toplevel)}")
proxy_socket=$(realpath -- "${CI_DEPENDENCY_PROXY_SOCKET:?Set the host-local Unix dependency cache socket}")
[[ -S "$proxy_socket" ]] || { echo 'Missing cache broker socket' >&2; exit 1; }
for dep in bwrap node npm python3 java skopeo; do command -v "$dep" >/dev/null || { echo "Missing offline smoke prerequisite $dep" >&2; exit 1; }; done
node_bin=$(realpath -- "$(command -v node)")
skopeo_bin=$(realpath -- "$(command -v skopeo)")
npm_cli=$(realpath -- "$(command -v npm)")
npm_package=$(dirname "$(dirname "$npm_cli")")
[[ -f "$npm_package/bin/npm-cli.js" ]] || { echo 'Cannot locate the installed npm CLI package' >&2; exit 1; }
python_bin=$(realpath -- "$(command -v python3)")
python_prefix=$("$python_bin" -c 'import sys; print(sys.base_prefix)')
"$python_bin" -m pip --version >/dev/null
java_binary=$(realpath -- "$(command -v java)")
java_home=$(dirname "$(dirname "$java_binary")")
# A read-only bind of the source would also expose .git, including checkout auth configuration.
# Export tracked candidate files (including uncommitted tracked edits) without Git metadata or untracked credentials.
staging=$(mktemp -d)
trap 'rm -rf -- "$staging"' EXIT
mkdir "$staging/source"
git -C "$source_dir" ls-files --cached -z | tar -C "$source_dir" --null --verbatim-files-from --no-recursion -T - -cf - | tar -C "$staging/source" -xf -
workdir=${CI_HERMETIC_WORKDIR:-.}
[[ "$workdir" != /* && "/$workdir/" != */../* && "$workdir" =~ ^[a-zA-Z0-9_./-]+$ ]] || { echo 'Invalid relative candidate working directory' >&2; exit 1; }
[[ -d "$staging/source/$workdir" ]] || { echo 'Missing candidate working directory' >&2; exit 1; }
# The trusted standalone harness can be run and tested before it has been added to a developer checkout.
# Candidate suites get their own read-only /source; the default smoke itself is supplied by the trusted checkout.
mkdir "$staging/harness"
cp "$launcher_dir/offline-relay.cjs" "$launcher_dir/smoke.cjs" "$launcher_dir/corretto.cjs" "$launcher_dir/gradle-wrapper.cjs" "$staging/harness/"
# These trusted, public registry mappings are literal data only; the guest never obtains the outer Docker socket.
node -e 'process.stdout.write(require(process.argv[1]).containersConfig("127.0.0.1", "__GUEST_PORT__"))' "$launcher_dir/client-routing.cjs" > "$staging/harness/registries.conf.in"
allow=(CI PYTEST_ADDOPTS KAFKA_TRAFFIC_TOPIC KAFKA_TRAFFIC_GROUP_ID OS_MIGRATIONS_GRADLE_SCAN_TOS_AGREE_AND_ENABLED CI_GRADLE_BUILD_CACHE_PUSH)
# Avoid arbitrary environment forwarding. Fixed independent test configuration is passed as arguments or these scalar values.
for name in "${allow[@]}"; do
  value=${!name-}; [[ -z "$value" || "$value" != *$'\n'* ]] || { echo "Invalid workflow value $name" >&2; exit 1; }
done
printf '#!/bin/sh\nexec /tools/bin/node /tools/npm/bin/npm-cli.js "$@"\n' > "$staging/npm"
chmod 0555 "$staging/npm"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$staging/hosts"
printf 'root:x:0:0:root:/nonexistent:/bin/false\nworker:x:%s:%s:worker:/home/worker:/bin/sh\n' "$(id -u)" "$(id -g)" > "$staging/passwd"
printf 'root:x:0:\nworker:x:%s:\n' "$(id -g)" > "$staging/group"
printf 'hosts: files dns\npasswd: files\ngroup: files\n' > "$staging/nsswitch.conf"
printf 'nameserver 127.0.0.1\n' > "$staging/resolv.conf"
# Ubuntu and Amazon Linux keep userland in /usr. Do not mount /run, host /etc, home directories, or runtime sockets.
args=(--unshare-all --unshare-user --disable-userns --die-with-parent --new-session --cap-drop ALL --clearenv
  --ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/sbin /sbin --symlink usr/lib /lib
  --proc /proc --dev /dev --tmpfs /tmp --dir /var --symlink /tmp /var/tmp
  --dir /run --dir /run/dependency-cache --ro-bind "$proxy_socket" /run/dependency-cache/cache.sock
  --dir /etc --ro-bind "$staging/hosts" /etc/hosts --ro-bind "$staging/resolv.conf" /etc/resolv.conf
  --ro-bind "$staging/passwd" /etc/passwd --ro-bind "$staging/group" /etc/group --ro-bind "$staging/nsswitch.conf" /etc/nsswitch.conf
  --dir /home --dir /home/worker --dir /work --dir /tools --dir /tools/bin
  --ro-bind "$staging/source" /source --ro-bind "$staging/harness" /harness
  --ro-bind "$node_bin" /tools/bin/node --ro-bind "$skopeo_bin" /tools/bin/skopeo --ro-bind "$java_home" /tools/jdk
  --ro-bind "$npm_package" /tools/npm --ro-bind "$staging/npm" /tools/bin/npm
  --setenv HOME /home/worker --setenv TMPDIR /tmp --setenv GRADLE_USER_HOME /home/worker/gradle --setenv JAVA_HOME /tools/jdk
  --setenv PATH /tools/bin:/tools/jdk/bin:/usr/bin:/bin --setenv LANG C.UTF-8 --setenv CI_HERMETIC_WORKDIR "$workdir" --chdir /work)
for name in "${allow[@]}"; do [[ -z "${!name+x}" ]] || args+=(--setenv "$name" "${!name}"); done
# The only writable host bind is a new empty export directory dedicated to this single invocation.
if [[ -n "${CI_HERMETIC_OUTPUT_DIR:-}" ]]; then
  output=$(realpath -m -- "$CI_HERMETIC_OUTPUT_DIR")
  [[ ! -e "$output" && ! -L "$CI_HERMETIC_OUTPUT_DIR" ]] || { echo 'Hermetic output must be a new dedicated directory' >&2; exit 1; }
  mkdir -m 700 -- "$output"
  args+=(--bind "$output" /export --setenv CI_HERMETIC_OUTPUT_DIR /export)
fi
library_dirs=()
if [[ "$python_prefix" != /usr ]]; then
  # Hosted CPython uses its installation path to locate its shared library and venv base interpreter.
  args+=(--ro-bind "$python_prefix" "$python_prefix" --symlink "$python_prefix/bin/python3" /tools/bin/python3)
  library_dirs+=("$python_prefix/lib")
fi
[[ ! -d /usr/lib64 ]] || args+=(--symlink usr/lib64 /lib64)
# Select only the local, public runtime data these binaries need; never mount all of /etc.
for dir in /etc/java /etc/ssl/certs /etc/crypto-policies /etc/pki/java /etc/pki/ca-trust/extracted/java /etc/ssl/certs/java; do
  [[ ! -e "$dir" ]] || args+=(--ro-bind "$(realpath "$dir")" "$dir")
done
if [[ -n "${CI_DEPENDENCY_TOOL_LIB_DIR:-}" ]]; then
  args+=(--ro-bind "$(realpath "$CI_DEPENDENCY_TOOL_LIB_DIR")" /tools/lib)
  library_dirs+=(/tools/lib)
fi
if (( ${#library_dirs[@]} )); then
  library_path=$(IFS=:; echo "${library_dirs[*]}")
  args+=(--setenv LD_LIBRARY_PATH "$library_path")
fi
if [[ -n "${GRADLE_BIN:-}" ]]; then
  gradle_dir=$(dirname "$(dirname "$(realpath "$GRADLE_BIN")")")
  args+=(--ro-bind "$gradle_dir" /tools/gradle --setenv GRADLE_BIN /tools/gradle/bin/gradle)
fi
if (($# == 0)); then set -- /tools/bin/node /harness/smoke.cjs; fi
# Ubuntu's AppArmor may deny unprivileged namespace creation. Avoid running the test tree as root:
# temporarily grant this exact root-owned bwrap executable permission to create a user namespace.
# All capability and mount restrictions are still installed by bwrap while running as the original runner UID.
profile=''
cleanup_host() {
  if [[ -n "$profile" ]]; then sudo -n apparmor_parser -R "$profile" >&2 || true; fi
  rm -rf -- "$staging"
}
trap cleanup_host EXIT
bubblewrap=$(realpath "$(command -v bwrap)")
if ! "$bubblewrap" --help | grep -q -- '--disable-userns'; then
  echo 'Hermetic jobs require bubblewrap 0.8+ (for example Ubuntu 24.04) to disable nested user namespaces' >&2; exit 1
fi
probe_namespace() { "$bubblewrap" --unshare-all --unshare-user --disable-userns --ro-bind / / -- /bin/true 2>/dev/null; }
if ! probe_namespace; then
  if [[ "$(stat -c '%u' "$bubblewrap")" != 0 || -w "$bubblewrap" || ! -r /sys/module/apparmor/parameters/enabled ]] ||
      ! command -v apparmor_parser >/dev/null || ! command -v sudo >/dev/null; then
    echo 'Cannot create an isolated user namespace as the runner account; verify the bwrap AppArmor profile' >&2; exit 1
  fi
  [[ "$bubblewrap" == /usr/bin/bwrap ]] || { echo 'Only the packaged /usr/bin/bwrap can receive CI AppArmor configuration' >&2; exit 1; }
  profile="$staging/bwrap.apparmor"
  name="migrations_bwrap_$(basename "$staging" | tr -cd '[:alnum:]')"
  printf 'abi <abi/4.0>,\ninclude <tunables/global>\nprofile %s %s flags=(unconfined) {\n  userns,\n  include if exists <local/%s>\n}\n' "$name" "$bubblewrap" "$name" > "$profile"
  sudo -n apparmor_parser -a "$profile"
  probe_namespace || { echo 'The packaged runner bwrap could not enter its user namespace after profile installation' >&2; exit 1; }
fi
launch=("$bubblewrap")
result=0
"${launch[@]}" "${args[@]}" -- /tools/bin/node /harness/offline-relay.cjs "$@" || result=$?
if [[ -n "${output:-}" ]]; then
  # Artifact uploaders must not follow a candidate-supplied link into the runner filesystem.
  find -P "$output" -xdev -mindepth 1 ! -type f ! -type d -delete
fi
exit "$result"
