#!/usr/bin/env bash
#
# migrationCompanion demo — Step 3: run the companion against the local
# kind cluster and generate a migration plan + empirical parity report.
#
# This wraps kiro-cli with the migration-companion agent and feeds it
# the two cluster endpoints created by 01-setup-cluster.sh. It will:
#   - probe both clusters
#   - draft a migration-plan.json
#   - validate + emit an Argo workflow
#   - submit the workflow, watch it finish, check parity
#   - drop artifacts under /tmp/companion-demo/
#
# Flags:
#   --interactive   Leave you in an interactive kiro-cli chat session
#                   after the initial turn (good for demos).
#   --autopilot     Non-interactive. Kiro runs to completion, then exits.
#                   (default)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo}"
mkdir -p "${OUT_DIR}"

MODE="autopilot"
for arg in "$@"; do
  case "$arg" in
    --interactive) MODE="interactive" ;;
    --autopilot)   MODE="autopilot" ;;
    *) echo "Unknown arg: $arg" >&2; exit 1 ;;
  esac
done

command -v kiro-cli >/dev/null 2>&1 || { echo "kiro-cli not found" >&2; exit 1; }
test -f "${HOME}/.kiro/agents/migration-companion.json" \
  || { echo "Run 02-install-steering.sh first." >&2; exit 1; }

# Load cluster versions written by 01-setup-cluster.sh. Fall back to safe
# defaults so this script still works if the user invokes it standalone.
SOURCE_ENGINE="elasticsearch"
SOURCE_VERSION="7.10.2"
TARGET_ENGINE="opensearch"
TARGET_VERSION="3.1.0"
if [[ -f "${OUT_DIR}/cluster-versions.env" ]]; then
  # shellcheck disable=SC1090
  source "${OUT_DIR}/cluster-versions.env"
fi

# Sanity: port-forwards up?
curl -sk -u admin:admin https://localhost:19200 >/dev/null \
  || { echo "Source not reachable on localhost:19200. Re-run 01-setup-cluster.sh." >&2; exit 1; }
curl -sk -u admin:admin https://localhost:19201 >/dev/null \
  || { echo "Target not reachable on localhost:19201. Re-run 01-setup-cluster.sh." >&2; exit 1; }

# The seed prompt Kiro sees — self-contained because the agent has no
# memory of this shell session.
read -r -d '' PROMPT <<EOF || true
Run an empirical migration plan for these clusters:

  Source (${SOURCE_ENGINE^} ${SOURCE_VERSION}):
    endpoint: https://localhost:19200
    auth: basic admin:admin
    allow_insecure: true

  Target (${TARGET_ENGINE^} ${TARGET_VERSION}):
    endpoint: https://localhost:19201
    auth: basic admin:admin
    allow_insecure: true

  Output directory: ${OUT_DIR}
  Repo root:        ${REPO_ROOT}
  Kubeconfig:       default (current context: kind-ma, namespace: ma)

The source/target engine and version are already known — DO NOT ask the
user to confirm them. Report the migration as
"${SOURCE_ENGINE^} ${SOURCE_VERSION} → ${TARGET_ENGINE^} ${TARGET_VERSION} Migration Report".

Do this in order, without asking for confirmation:

  1. Probe both clusters:
       python3 ${REPO_ROOT}/migrationCompanion/scripts/probe_source.py \\
         --source https://localhost:19200 --source-auth admin:admin \\
         --target https://localhost:19201 --target-auth admin:admin \\
         --allow-insecure --out ${OUT_DIR}/probe.json

  2. Draft ${OUT_DIR}/plan.json conforming to
     migrationCompanion/schemas/migration-plan.schema.json. Use
     createSnapshotConfig (NOT BYOS), allow_insecure=true, and include
     all three source indices (products, legacy_orders, events-2025.05).

  3. Validate it:
       python3 ${REPO_ROOT}/migrationCompanion/scripts/validate_plan.py \\
         ${OUT_DIR}/plan.json

  4. Emit + submit:
       python3 ${REPO_ROOT}/migrationCompanion/scripts/run_empirical.py \\
         --plan ${OUT_DIR}/plan.json --out ${OUT_DIR} --namespace ma

  5. When the workflow finishes, run parity:
       python3 ${REPO_ROOT}/migrationCompanion/scripts/parity_check.py \\
         --plan ${OUT_DIR}/plan.json --out ${OUT_DIR}/parity.json

  6. Write ${OUT_DIR}/report.md summarizing: workflow phase, per-index
     doc-count parity, mapping/settings diffs, total wall-clock. Keep
     it under 60 lines.

Stop after step 6. Print the path to report.md.
EOF

case "${MODE}" in
  autopilot)
    echo "Running in autopilot mode. Artifacts will land in ${OUT_DIR}"
    kiro-cli chat \
      --agent migration-companion \
      --model claude-opus-4.7 \
      --trust-all-tools \
      --no-interactive \
      "${PROMPT}"
    echo
    echo "Done. Artifacts:"
    ls -la "${OUT_DIR}"
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
