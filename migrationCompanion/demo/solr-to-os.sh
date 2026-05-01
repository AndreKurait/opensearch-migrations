#!/usr/bin/env bash
#
# migrationCompanion demo — Apache Solr 8 → OpenSearch 3.x
#
# End-to-end: stands up a kind cluster with MA + target OS (via kindTesting.sh),
# deploys a single-pod Solr 8.11.4 in the ma namespace (plain Deployment, no
# operator, no CRDs — mirrors the TestCreateSnapshotSolrS3 testcontainer setup),
# seeds two SolrCloud collections with demo documents, then hands off to the
# companion agent. MA's CreateSnapshot (sourceType=solr) handles the S3 push to
# the in-namespace localstack directly — no aws-cli sidecar, no shared volumes.
#
# Flags:
#   --interactive   Drop into interactive kiro-cli chat after setup.
#   --autopilot     (default) Kiro runs end-to-end non-interactively.
#   --skip-setup    Assume clusters are already up; just run the agent.
#
# Idempotent. Wall-clock: ~10-15 min cold, ~3-5 min warm.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="kind-${KIND_CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-ma}"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo}"

SOURCE_VERSION="${SOURCE_VERSION:-8.11.4}"
# Target OpenSearch version is owned by testClusters/valuesCompanionDemo.yaml.
# Keep a string here only for labelling the status banner; changing it has no
# effect on the deployed target image.
TARGET_VERSION="3.5.0"
MODE="autopilot"
SKIP_SETUP="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interactive) MODE="interactive"; shift ;;
    --autopilot)   MODE="autopilot";   shift ;;
    --skip-setup)  SKIP_SETUP="true";  shift ;;
    --source-version) SOURCE_VERSION="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

say()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

for bin in docker kind kubectl jq curl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required tool: $bin" >&2; exit 1; }
done

mkdir -p "${OUT_DIR}"

if [[ "${SKIP_SETUP}" != "true" ]]; then
  say "Bringing up kind cluster '${KIND_CLUSTER_NAME}' with MA + default (ES+OS) test clusters"
  cd "${REPO_ROOT}"
  bash deployment/k8s/kindTesting.sh

  say "Applying companion-demo target overlay (OpenSearch ${TARGET_VERSION})"
  # The shared testClusters values.yaml pins target.image.tag to an older
  # version for the repo's test matrix; valuesCompanionDemo.yaml overrides
  # it for the companion demo path. Native config idiom — not --set.
  helm --kube-context "${KIND_CONTEXT}" upgrade tc \
    "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters" \
    -n "${NAMESPACE}" --reuse-values --wait --timeout 5m \
    -f "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters/valuesCompanionDemo.yaml"

  say "Deploying standalone Solr ${SOURCE_VERSION} into namespace ${NAMESPACE}"
  # Plain Deployment + Service — no solr-operator, no CRDs, no ZooKeeper CRD,
  # no chart overlays. Matches the pattern used by TestCreateSnapshotSolrS3
  # (CreateSnapshot/src/test/java/org/opensearch/migrations/TestCreateSnapshotSolrS3.java).
  kubectl --context "${KIND_CONTEXT}" apply -f \
    "${REPO_ROOT}/migrationCompanion/demo/solr8-standalone.yaml"

  say "Waiting for Solr + OS target pods to be Ready"
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" rollout status deploy/solr --timeout=300s
  # The target OpenSearch cluster is a StatefulSet named opensearch-cluster-master
  # from the 'target' subchart (labels: app.kubernetes.io/name=target,
  # app.kubernetes.io/component=opensearch-cluster-master). rollout status on the
  # StatefulSet is label-agnostic and waits for all replicas to be Ready.
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    rollout status sts/opensearch-cluster-master --timeout=600s

  say "Port-forwarding Solr -> localhost:18983, OS target -> localhost:19201"
  pkill -f "port-forward.*svc/solr"                  2>/dev/null || true
  pkill -f "port-forward.*opensearch-cluster-master" 2>/dev/null || true
  sleep 1
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward svc/solr 18983:8983 >/tmp/pf-solr.log 2>&1 &
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward svc/opensearch-cluster-master 19201:9200 >/tmp/pf-target.log 2>&1 &

  for i in $(seq 1 60); do
    if curl -sf "http://localhost:18983/solr/admin/collections?action=LIST&wt=json" >/dev/null 2>&1 \
       && curl -sk -u 'admin:myStrongPassword123!' "https://localhost:19201" >/dev/null 2>&1; then
      ok "Port-forwards live"
      break
    fi
    sleep 2
  done

  SOLR="http://localhost:18983/solr"

  say "Seeding Solr with fixture collections (techproducts, films)"
  # techproducts — canonical Solr demo corpus (electronics/accessory facet)
  curl -sf "${SOLR}/admin/collections?action=CREATE&name=techproducts&numShards=1&replicationFactor=1&wt=json" >/dev/null \
    || warn "techproducts may already exist; continuing"

  TP_DOCS='['
  for i in $(seq 1 30); do
    TP_DOCS+="{\"id\":\"TP-${i}\",\"name\":\"Gadget ${i}\",\"manu\":\"Acme\",\"cat\":[\"electronics\",\"accessory\"],\"features\":\"fast durable premium unit ${i}\",\"price\":$((20 + RANDOM % 400)).99,\"inStock\":true},"
  done
  TP_DOCS="${TP_DOCS%,}]"
  curl -sf -H 'Content-Type: application/json' \
    -d "${TP_DOCS}" \
    "${SOLR}/techproducts/update?commit=true" >/dev/null

  # films — director/genre/date facet corpus
  curl -sf "${SOLR}/admin/collections?action=CREATE&name=films&numShards=1&replicationFactor=1&wt=json" >/dev/null \
    || warn "films may already exist; continuing"

  FILM_DOCS='['
  GENRES=(Drama Comedy Action Sci-Fi Horror Documentary)
  for i in $(seq 1 20); do
    G="${GENRES[$((RANDOM % ${#GENRES[@]}))]}"
    FILM_DOCS+="{\"id\":\"F-${i}\",\"name\":\"Film ${i}\",\"directed_by\":[\"Director ${i}\"],\"genre\":[\"${G}\"],\"initial_release_date\":\"20$((10 + i % 15))-0$((1 + i % 9))-15T00:00:00Z\"},"
  done
  FILM_DOCS="${FILM_DOCS%,}]"
  curl -sf -H 'Content-Type: application/json' \
    -d "${FILM_DOCS}" \
    "${SOLR}/films/update?commit=true" >/dev/null

  ok "Source seeded. Collection counts:"
  curl -s "${SOLR}/techproducts/select?q=*:*&rows=0&wt=json" | jq -r '"techproducts: " + (.response.numFound|tostring)'
  curl -s "${SOLR}/films/select?q=*:*&rows=0&wt=json"        | jq -r '"films:        " + (.response.numFound|tostring)'
fi

# Register the companion skill as a Kiro agent if it's missing.
if [[ ! -f "${HOME}/.kiro/agents/migration-companion.json" ]]; then
  say "Installing companion skill as Kiro agent"
  bash "${REPO_ROOT}/migrationCompanion/demo/install-skill.sh"
fi

# Pre-create target creds. OS 2.12+ demo installer requires OPENSEARCH_INITIAL_ADMIN_PASSWORD;
# testClusters chart sets it to the password below. Solr demo has no source auth.
say "Ensuring demo secrets exist in namespace ${NAMESPACE}"
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" create secret generic target-creds \
  --from-literal=username=admin --from-literal='password=myStrongPassword123!' \
  --dry-run=client -o yaml | kubectl --context "${KIND_CONTEXT}" apply -f - >/dev/null
ok "Secrets present"

say "Handoff to companion agent"

read -r -d '' PROMPT <<EOF || true
Source:  Solr ${SOURCE_VERSION}          http://localhost:18983  (no auth)
         In-cluster: http://solr.${NAMESPACE}.svc.cluster.local:8983
Target:  OpenSearch ${TARGET_VERSION}    https://localhost:19201 (basic, target-creds)
Target uses self-signed TLS; set allowInsecure: true on the target cluster.
Scope:   all non-system collections (techproducts, films).
Mode:    snapshot-only, skipApprovals: true (demo).
Output:  ${REPO_ROOT}/migrationCompanion/runs/<timestamp>/

Drive the migration end-to-end per SKILL.md:
  Phase 0 — schema refresh (cat /root/schema/workflowMigration.schema.json inside pod)
  Phase 1 — probe Solr (/solr/admin/collections?action=LIST, schema, luke)
            and target OS via curl. Skip the interview for these already-
            confirmed fields; do still pick deep-validate collections.
  Phase 2 — scaffold config.yaml against the live schema. Source version
            MUST be literal "SOLR ${SOURCE_VERSION}" (uppercase SOLR).
            Source endpoint in config: "http://solr:8983" (in-cluster DNS).
  Phase 3 — submit via workflow submit, watch.
  Phase 4/5 — structural parity + 5-8 query-shape tests translated from
              Solr q/fq/facet to OpenSearch DSL, plus 2 relevancy showcase
              queries. Pick the queries from what you see during probe.
              No fabricated tokens. Note analyzer differences explicitly
              (Solr text_general vs OS standard).
  Phase 6 — write report.md to the run dir, including the Solr→OS query
            translation table.

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
