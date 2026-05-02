#!/usr/bin/env bash
#
# migrationCompanion demo — Solr 9.x → OpenSearch 3.x (via Translation Shim)
#
# End-to-end POC: stands up Solr + OpenSearch + Translation Shim via docker
# compose (from solrMigrationDevSandbox/), seeds identical data into both,
# hands control to the companion agent to analyze query-plane parity through
# the shim.
#
# The Solr path is structurally different from the ES path:
#   - No `workflow submit` — documents are loaded into both clusters
#     identically via the sandbox setup script (simulating a successful
#     data-plane migration). This demo focuses on QUERY-PLANE parity,
#     which is the Solr-specific surface the Translation Shim exists to
#     address.
#   - Phase 5b (steering/06-shim-analysis.md) drives query execution
#     through the shim and cross-validates against direct Solr.
#
# Flags:
#   --interactive   Drop into interactive kiro-cli chat after setup.
#   --autopilot     (default) Kiro runs end-to-end non-interactively.
#   --skip-setup    Assume sandbox is already up; just run the agent.
#   --no-teardown   Leave containers running after the agent finishes.
#
# Idempotent. Wall-clock: ~10-20 min cold, ~2-4 min warm.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SANDBOX_DIR="${REPO_ROOT}/solrMigrationDevSandbox"
OUT_DIR="${OUT_DIR:-/tmp/companion-solr-demo}"

SOURCE_VERSION="${SOURCE_VERSION:-9.7.0}"
TARGET_VERSION="3.3.0"   # pinned by solrMigrationDevSandbox/docker-compose.yml
MODE="autopilot"
SKIP_SETUP="false"
NO_TEARDOWN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interactive) MODE="interactive"; shift ;;
    --autopilot)   MODE="autopilot";   shift ;;
    --skip-setup)  SKIP_SETUP="true";  shift ;;
    --no-teardown) NO_TEARDOWN="true"; shift ;;
    --source-version) SOURCE_VERSION="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

say()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

for bin in docker python3 jq curl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required tool: $bin" >&2; exit 1; }
done

# docker compose v2 plugin must be available (not just 'docker-compose' v1).
if ! docker compose version >/dev/null 2>&1; then
  echo "'docker compose' (v2 plugin) not available. Install to ~/.docker/cli-plugins/docker-compose." >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

# -----------------------------------------------------------------------------
# Step 1: Bring up the sandbox (Solr + OS + Translation Shim)
# -----------------------------------------------------------------------------

if [[ "${SKIP_SETUP}" != "true" ]]; then
  say "Bringing up Solr ${SOURCE_VERSION} + OpenSearch ${TARGET_VERSION} + Translation Shim"
  say "  (via ${SANDBOX_DIR}/run.sh — this may take 10-15 min cold)"

  # Ensure a local docker registry exists on :5001 — jib tags the shim image
  # to localhost:5001/migrations/transformation_shim, and compose pulls by
  # that name. If no registry is running, create one (harmless if already up).
  if ! curl -sf http://localhost:5001/v2/ >/dev/null 2>&1; then
    say "Starting local docker registry on :5001 (for shim image)"
    docker rm -f registry-5001 >/dev/null 2>&1 || true
    docker run -d -p 127.0.0.1:5001:5000 --name registry-5001 \
      --restart=always registry:2 >/dev/null
    for _ in $(seq 1 30); do
      curl -sf http://localhost:5001/v2/ >/dev/null 2>&1 && break
      sleep 1
    done
  fi

  cd "${SANDBOX_DIR}"
  # --run-queries would execute 167 queries non-interactively and tear down.
  # We want the sandbox to stay up so the companion agent can drive queries
  # through the shim itself — that's the whole point of the agent demo.
  bash run.sh
  cd - >/dev/null

  # Sanity check endpoints.
  for url in \
    http://localhost:18983/solr/admin/info/system \
    http://localhost:19200/ \
    http://localhost:18080/solr/admin/info/system \
    http://localhost:18084/solr/admin/info/system ; do
    if curl -sf "$url" >/dev/null 2>&1; then
      ok "Up: $url"
    else
      warn "Not responding: $url"
    fi
  done
else
  say "Skipping setup (--skip-setup). Assuming sandbox is already up."
fi

# -----------------------------------------------------------------------------
# Step 2: Register the companion skill with kiro-cli (idempotent).
# -----------------------------------------------------------------------------

if [[ ! -f "${HOME}/.kiro/agents/migration-companion.json" ]]; then
  say "Installing companion skill as Kiro agent"
  bash "${REPO_ROOT}/migrationCompanion/demo/install-skill.sh"
else
  # Re-run install-skill.sh anyway so resource list stays in sync with the
  # steering files on disk (cheap; idempotent).
  bash "${REPO_ROOT}/migrationCompanion/demo/install-skill.sh" >/dev/null
fi

# -----------------------------------------------------------------------------
# Step 3: Handoff to the companion agent.
# -----------------------------------------------------------------------------

say "Handoff to companion agent"

read -r -d '' PROMPT <<EOF || true
Source:       Solr ${SOURCE_VERSION}       http://localhost:18983     (no auth)
Target:       OpenSearch ${TARGET_VERSION} http://localhost:19200     (no auth)
Shim (single):                             http://localhost:18080     (OS-only)
Shim (dual, OS primary):                   http://localhost:18084     (OS primary, Solr witness)
Shim (dual, Solr primary):                 http://localhost:18083     (Solr primary, OS witness)

Data loading:
  Source and target have been loaded with IDENTICAL data (the
  solrMigrationDevSandbox setup script does a one-time synthetic load
  simulating a completed data-plane migration). You do NOT need to run
  \`workflow submit\`; the data-plane is already converged.

Run layout:
  Scope:       all non-system collections (the sandbox loads one:
               a products_catalog collection + the OS-side mirror).
  Output:      ${REPO_ROOT}/migrationCompanion/runs/<timestamp>/

Drive the migration-evaluation end-to-end per SKILL.md, skipping the
phases that are not meaningful for Solr + already-converged data-plane:

  Phase 0    — schema refresh is N/A (no migration-console, no workflow
               submit); skip and note the skip in §2 of the report.
  Phase 1    — probe BOTH clusters directly (curl
               http://localhost:18983/solr/admin/info/system,
               http://localhost:19200/) AND the shim
               (curl http://localhost:18080/solr/admin/info/system);
               record versions, collections, doc counts, sample fields.
  Phase 2/3  — SKIP (data-plane is already converged). Note why in §1.
  Phase 4/5  — structural parity: confirm doc counts match 1:1 on every
               collection, byte-compare Solr schema.xml-derived field
               list vs OS mapping where sensible.
  Phase 5b   — shim analysis per steering/06-shim-analysis.md. Run the
               sandbox's in-tree batch:
                   cd ${SANDBOX_DIR} && python3 -m src.run_queries \\
                     --solr-url http://localhost:18983 \\
                     --shim-url http://localhost:18080 \\
                     --dual-url http://localhost:18084 \\
                     --queries queries/queries.json
               then extract the FileSystem-sink dumps from the
               shim-reports-os docker volume into runs/<ts>/shim-reports-os/,
               and aggregate shim-summary.json per the steering file.
  Phase 6    — write report.md. Include a §S "Translation-shim analysis"
               section between §6 and §7 with classified failure
               counts (round-trip / noise / rank-drift / transform-bug),
               a per-category drift table, and 2-3 concrete
               validation_failures entries cited by file.

Stop after Phase 6. Print the path to report.md.
EOF

case "${MODE}" in
  autopilot)
    RUNS_DIR="${REPO_ROOT}/migrationCompanion/runs"
    BEFORE="$(ls -1 "${RUNS_DIR}" 2>/dev/null | sort | tail -1 || true)"
    echo "Running autopilot — artifacts will land under ${RUNS_DIR}/"
    echo "--- begin companion session ---"
    stdbuf -oL -eL kiro-cli chat \
      --agent migration-companion \
      --model claude-opus-4.7 \
      --trust-all-tools \
      --no-interactive \
      "${PROMPT}" || true
    echo "--- end companion session ---"

    LATEST="$(ls -1 "${RUNS_DIR}" 2>/dev/null | sort | tail -1 || true)"
    if [[ -n "${LATEST}" && "${LATEST}" != "${BEFORE}" && -f "${RUNS_DIR}/${LATEST}/report.md" ]]; then
      ok "Run artifacts: ${RUNS_DIR}/${LATEST}/"
      ok "Report:         ${RUNS_DIR}/${LATEST}/report.md"
    else
      warn "No report.md was produced."
      if [[ -n "${LATEST}" && "${LATEST}" != "${BEFORE}" ]]; then
        warn "Run dir exists but is incomplete: ${RUNS_DIR}/${LATEST}/"
      fi
      warn "Resume the session with:  kiro-cli chat --agent migration-companion --resume"
    fi
    ;;
  interactive)
    echo "Starting interactive companion session. Ctrl-D to exit."
    kiro-cli chat \
      --agent migration-companion \
      --model claude-opus-4.7 \
      --trust-all-tools \
      "${PROMPT}"
    ;;
esac

if [[ "${SKIP_SETUP}" != "true" && "${NO_TEARDOWN}" != "true" ]]; then
  say "Tearing down sandbox (pass --no-teardown to keep it up)"
  cd "${SANDBOX_DIR}" && docker compose down -v >/dev/null 2>&1 || true
fi
