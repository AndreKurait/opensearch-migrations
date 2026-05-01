#!/usr/bin/env bash
#
# migrationCompanion demo — Step 0: wipe everything.
#
# Destroys:
#   - kind cluster named ${KIND_CLUSTER_NAME:-ma} (default: ma)
#   - background kubectl port-forwards on 19200/19201
#   - /tmp/companion-demo/ artifacts (report.md, plan.json, parity.json, ...)
#   - /tmp/pf-source.log, /tmp/pf-target.log
#   - ~/.kiro/agents/migration-companion.json
#
# Does NOT touch: your git checkout, docker images (for warm rebuilds),
# the local kind registry container (reused across runs).
#
# Re-runnable: safe to run when nothing exists yet.

set -euo pipefail

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo}"
AGENT_FILE="${HOME}/.kiro/agents/migration-companion.json"

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

say "Killing port-forwards on 19200/19201"
pkill -f "port-forward.*elasticsearch-master" 2>/dev/null || true
pkill -f "port-forward.*opensearch-cluster-master" 2>/dev/null || true
pkill -f "port-forward.*19200:9200"            2>/dev/null || true
pkill -f "port-forward.*19201:9200"            2>/dev/null || true
ok "Port-forwards cleared"

say "Deleting kind cluster '${KIND_CLUSTER_NAME}'"
if command -v kind >/dev/null 2>&1; then
  if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER_NAME}"; then
    kind delete cluster --name "${KIND_CLUSTER_NAME}"
    ok "Cluster deleted"
  else
    ok "No cluster named '${KIND_CLUSTER_NAME}' — skipping"
  fi
else
  echo "kind not installed — skipping cluster delete"
fi

say "Removing demo artifacts"
rm -rf "${OUT_DIR}"
rm -f  /tmp/pf-source.log /tmp/pf-target.log
rm -f  "${AGENT_FILE}"
ok "Artifacts cleared: ${OUT_DIR}, port-forward logs, ${AGENT_FILE}"

cat <<EOF

────────────────────────────────────────────────────────────────
  Reset complete
────────────────────────────────────────────────────────────────
  kind cluster:      deleted (${KIND_CLUSTER_NAME})
  port-forwards:     killed (19200, 19201)
  /tmp/companion-demo: removed
  Kiro agent:        removed (${AGENT_FILE})

  Next: bash migrationCompanion/demo/01-setup-cluster.sh
────────────────────────────────────────────────────────────────
EOF
