#!/usr/bin/env bash
#
# migrationCompanion demo — Step 1: bring up a kind cluster with
# Migration Assistant (MA) + Argo + a source ES cluster + a target OS cluster,
# then seed the source with synthetic fixture data the companion can reason about.
#
# Idempotent. Re-running is safe; existing clusters are reused.
#
# Requirements on the host:
#   docker, kind, kubectl, helm, jq, curl
#   Optional: python3 (only used for seed scripts)
#
# Expected wall-clock on a warm laptop:
#   ~15–25 min cold (image builds), ~3–5 min warm (reuse cluster + registry).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="kind-${KIND_CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-ma}"

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

for bin in docker kind kubectl helm jq curl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required tool: $bin" >&2; exit 1; }
done

say "Bringing up kind cluster '${KIND_CLUSTER_NAME}' and installing MA + test clusters (Elasticsearch source + OpenSearch target)"
cd "${REPO_ROOT}"
# The in-tree script handles: kind cluster, local docker registry,
# image builds, MA helm chart, Argo, and test-clusters (ES + OS).
bash deployment/k8s/kindTesting.sh

say "Waiting for source (Elasticsearch) and target (OpenSearch) pods to be Ready"
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready pod \
  -l 'app in (elasticsearch-master, opensearch-cluster-master)' --timeout=600s

say "Starting port-forwards (source -> localhost:19200, target -> localhost:19201)"
# Kill any prior forwards so this script stays idempotent
pkill -f "port-forward.*elasticsearch-master" 2>/dev/null || true
pkill -f "port-forward.*opensearch-cluster-master" 2>/dev/null || true
sleep 1
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
  port-forward svc/elasticsearch-master 19200:9200 >/tmp/pf-source.log 2>&1 &
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
  port-forward svc/opensearch-cluster-master 19201:9200 >/tmp/pf-target.log 2>&1 &

# Wait for forwards to be reachable
for i in {1..30}; do
  if curl -sk -u admin:admin https://localhost:19200 >/dev/null 2>&1 \
     && curl -sk -u admin:admin https://localhost:19201 >/dev/null 2>&1; then
    ok "Port-forwards live"
    break
  fi
  sleep 2
done

say "Seeding source with synthetic fixture data (products, legacy_orders, events-2025.05)"
SRC="https://localhost:19200"
CURL=(curl -sk -u admin:admin -H 'Content-Type: application/json')

# products: 50 simple docs
"${CURL[@]}" -XPUT "$SRC/products" -d '{"settings":{"number_of_shards":3,"number_of_replicas":0}}' >/dev/null || true
for i in $(seq 1 50); do
  "${CURL[@]}" -XPOST "$SRC/products/_doc" \
    -d "{\"sku\":\"P-${i}\",\"price\":$((RANDOM % 500)),\"in_stock\":true}" >/dev/null
done

# legacy_orders: 40 docs, single shard
"${CURL[@]}" -XPUT "$SRC/legacy_orders" -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}' >/dev/null || true
for i in $(seq 1 40); do
  "${CURL[@]}" -XPOST "$SRC/legacy_orders/_doc" \
    -d "{\"order_id\":${i},\"customer\":\"C-${i}\",\"total\":$((RANDOM % 1000))}" >/dev/null
done

# events-2025.05: 25 docs, time-series style
"${CURL[@]}" -XPUT "$SRC/events-2025.05" -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}' >/dev/null || true
for i in $(seq 1 25); do
  "${CURL[@]}" -XPOST "$SRC/events-2025.05/_doc" \
    -d "{\"event\":\"click\",\"user\":\"U-${i}\",\"ts\":\"2025-05-01T00:00:${i}Z\"}" >/dev/null
done

"${CURL[@]}" -XPOST "$SRC/_refresh" >/dev/null

ok "Source indices seeded:"
"${CURL[@]}" "$SRC/_cat/indices?v" | grep -E 'products|legacy_orders|events' || true

cat <<EOF

────────────────────────────────────────────────────────────────
  Cluster is READY
────────────────────────────────────────────────────────────────
  Source (Elasticsearch 8.5.1):  https://localhost:19200  (admin:admin)
  Target (OpenSearch 2.x):       https://localhost:19201  (admin:admin)
  Argo UI:                       kubectl -n ${NAMESPACE} port-forward svc/argo-server 2746:2746
  Console:                       kubectl -n ${NAMESPACE} exec -it migration-console-0 -- bash

  Next steps:
    2) bash migrationCompanion/demo/02-install-steering.sh
    3) bash migrationCompanion/demo/03-run-companion.sh
────────────────────────────────────────────────────────────────
EOF
