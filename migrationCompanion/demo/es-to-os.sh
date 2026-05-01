#!/usr/bin/env bash
#
# migrationCompanion demo — Elasticsearch 7.x → OpenSearch 3.x
#
# End-to-end: stands up a kind cluster with MA + source ES + target OS,
# seeds the source with fixture data, then hands control to the companion
# agent (loaded as a Kiro agent via install-skill.sh).
#
# Flags:
#   --interactive   Drop into interactive kiro-cli chat after setup.
#   --autopilot     (default) Kiro runs end-to-end non-interactively.
#   --skip-setup    Assume clusters are already up; just run the agent.
#
# Idempotent. Wall-clock: ~15-25 min cold, ~3-5 min warm.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="kind-${KIND_CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-ma}"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo}"

SOURCE_VERSION="${SOURCE_VERSION:-7.10.2}"
TARGET_VERSION="${TARGET_VERSION:-3.5.0}"
MODE="autopilot"
SKIP_SETUP="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interactive) MODE="interactive"; shift ;;
    --autopilot)   MODE="autopilot";   shift ;;
    --skip-setup)  SKIP_SETUP="true";  shift ;;
    --source-version) SOURCE_VERSION="$2"; shift 2 ;;
    --target-version) TARGET_VERSION="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

say()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

for bin in docker kind kubectl helm jq curl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required tool: $bin" >&2; exit 1; }
done

mkdir -p "${OUT_DIR}"

if [[ "${SKIP_SETUP}" != "true" ]]; then
  if [[ "${SOURCE_VERSION}" != "7.10.2" ]]; then
    warn "SOURCE_VERSION=${SOURCE_VERSION} — in-tree ES image is pinned to 7.10.2."
    warn "Deploying 7.10.2 anyway; report labelling will use ${SOURCE_VERSION}."
  fi

  say "Bringing up kind cluster '${KIND_CLUSTER_NAME}' with MA + ES source + OS ${TARGET_VERSION} target"
  cd "${REPO_ROOT}"
  bash deployment/k8s/kindTesting.sh

  say "Overriding target OpenSearch image.tag to ${TARGET_VERSION}"
  helm --kube-context "${KIND_CONTEXT}" upgrade tc \
    "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters" \
    -n "${NAMESPACE}" --reuse-values --wait --timeout 5m \
    --set "target.image.tag=${TARGET_VERSION}"

  say "Waiting for ES + OS pods to be Ready"
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready pod \
    -l 'app in (elasticsearch-master, opensearch-cluster-master)' --timeout=600s

  say "Starting port-forwards (source -> 19200, target -> 19201)"
  pkill -f "port-forward.*elasticsearch-master"      2>/dev/null || true
  pkill -f "port-forward.*opensearch-cluster-master" 2>/dev/null || true
  sleep 1
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward svc/elasticsearch-master 19200:9200 >/tmp/pf-source.log 2>&1 &
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward svc/opensearch-cluster-master 19201:9200 >/tmp/pf-target.log 2>&1 &

  for i in $(seq 1 30); do
    if curl -sk -u admin:admin https://localhost:19200 >/dev/null 2>&1 \
       && curl -sk -u admin:admin https://localhost:19201 >/dev/null 2>&1; then
      ok "Port-forwards live"
      break
    fi
    sleep 2
  done

  say "Seeding source with fixture data (products, legacy_orders, events-2025.05)"
  SRC="https://localhost:19200"
  CURL=(curl -sk -u admin:admin -H 'Content-Type: application/json')

  "${CURL[@]}" -XPUT "$SRC/products" \
    -d '{"settings":{"number_of_shards":3,"number_of_replicas":0}}' >/dev/null || true
  for i in $(seq 1 50); do
    "${CURL[@]}" -XPOST "$SRC/products/_doc" \
      -d "{\"sku\":\"P-${i}\",\"name\":\"Widget ${i}\",\"price\":$((RANDOM % 500)),\"in_stock\":true}" >/dev/null
  done

  "${CURL[@]}" -XPUT "$SRC/legacy_orders" \
    -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}' >/dev/null || true
  for i in $(seq 1 40); do
    "${CURL[@]}" -XPOST "$SRC/legacy_orders/_doc" \
      -d "{\"order_id\":${i},\"customer\":\"C-${i}\",\"total\":$((RANDOM % 1000))}" >/dev/null
  done

  "${CURL[@]}" -XPUT "$SRC/events-2025.05" \
    -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}' >/dev/null || true
  for i in $(seq 1 25); do
    "${CURL[@]}" -XPOST "$SRC/events-2025.05/_doc" \
      -d "{\"event\":\"click\",\"user\":\"U-${i}\",\"ts\":\"2025-05-01T00:00:${i}Z\"}" >/dev/null
  done

  "${CURL[@]}" -XPOST "$SRC/_refresh" >/dev/null
  ok "Source seeded:"
  "${CURL[@]}" "$SRC/_cat/indices?v" | grep -E 'products|legacy_orders|events' || true
fi

# Ensure the companion agent is registered.
if [[ ! -f "${HOME}/.kiro/agents/migration-companion.json" ]]; then
  say "Installing companion skill as Kiro agent"
  bash "${REPO_ROOT}/migrationCompanion/demo/install-skill.sh"
fi

# Pre-create source-creds / target-creds secrets so the agent doesn't
# have to interview for them in the demo.
say "Ensuring demo secrets exist in namespace ${NAMESPACE}"
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" create secret generic source-creds \
  --from-literal=username=admin --from-literal=password=admin \
  --dry-run=client -o yaml | kubectl --context "${KIND_CONTEXT}" apply -f - >/dev/null
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" create secret generic target-creds \
  --from-literal=username=admin --from-literal=password=admin \
  --dry-run=client -o yaml | kubectl --context "${KIND_CONTEXT}" apply -f - >/dev/null
ok "Secrets present"

say "Handoff to companion agent"

read -r -d '' PROMPT <<EOF || true
Source:  Elasticsearch ${SOURCE_VERSION}  https://localhost:19200  (basic, source-creds)
Target:  OpenSearch ${TARGET_VERSION}     https://localhost:19201  (basic, target-creds)
Both use self-signed TLS; set allowInsecure: true on both clusters.
Scope:   all non-system indices (products, legacy_orders, events-2025.05).
Mode:    snapshot-only, skipApprovals: true (demo).
Output:  ${REPO_ROOT}/migrationCompanion/runs/<timestamp>/

Drive the migration end-to-end per SKILL.md:
  Phase 0 — schema refresh (cat /root/.workflowUser.schema.json inside pod)
  Phase 1 — probe both clusters via curl (skip the interview for these
            already-confirmed fields; do still pick deep-validate indices)
  Phase 2 — scaffold config.yaml against the live schema
  Phase 3 — submit via workflow submit, watch
  Phase 4/5 — structural parity + 5-8 query-shape tests + 2 relevancy
              showcase queries. You pick the queries from what you see
              during probe. No fabricated tokens.
  Phase 6 — write report.md to the run dir.

Stop after Phase 6. Print the path to report.md.
EOF

case "${MODE}" in
  autopilot)
    echo "Running autopilot — artifacts will land under ${REPO_ROOT}/migrationCompanion/runs/"
    kiro-cli chat \
      --agent migration-companion \
      --model claude-opus-4.7 \
      --trust-all-tools \
      --no-interactive \
      "${PROMPT}"
    echo
    ls -la "${REPO_ROOT}/migrationCompanion/runs" 2>/dev/null || true
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
