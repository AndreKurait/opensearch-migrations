#!/usr/bin/env bash
#
# migrationCompanion demo — reset.
#
# Destroys:
#   - kind cluster named ${KIND_CLUSTER_NAME:-ma}
#   - background kubectl port-forwards on 19200/19201/19202
#   - /tmp/companion-demo/ artifacts
#   - migrationCompanion/runs/ (per-run outputs)
#   - ~/.kiro/agents/migration-companion.json
#
# Does NOT touch: git checkout, docker images, local kind registry.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo}"
AGENT_FILE="${HOME}/.kiro/agents/migration-companion.json"

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

say "Killing port-forwards on 19200/19201/19202"
pkill -f "port-forward.*elasticsearch-master"          2>/dev/null || true
pkill -f "port-forward.*opensearch-cluster-master"     2>/dev/null || true
pkill -f "port-forward.*solr"                          2>/dev/null || true
pkill -f "port-forward.*1920[012]:"                    2>/dev/null || true
ok "Port-forwards cleared"

say "Deleting kind cluster '${KIND_CLUSTER_NAME}'"
if command -v kind >/dev/null 2>&1; then
  if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER_NAME}"; then
    kind delete cluster --name "${KIND_CLUSTER_NAME}"
    ok "Cluster deleted"
  else
    ok "No cluster named '${KIND_CLUSTER_NAME}' — skipping"
  fi
fi

say "Removing demo artifacts"
rm -rf "${OUT_DIR}"
rm -rf "${REPO_ROOT}/migrationCompanion/runs"
rm -f  /tmp/pf-source.log /tmp/pf-target.log
rm -f  "${AGENT_FILE}"
ok "Artifacts cleared"

cat <<EOF

────────────────────────────────────────────────────────────────
  Reset complete
────────────────────────────────────────────────────────────────
  kind cluster:        deleted (${KIND_CLUSTER_NAME})
  port-forwards:       killed
  /tmp/companion-demo: removed
  migrationCompanion/runs: removed
  Kiro agent:          removed

  Next: bash migrationCompanion/demo/es-to-os.sh
────────────────────────────────────────────────────────────────
EOF
