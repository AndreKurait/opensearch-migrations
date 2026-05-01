#!/usr/bin/env bash
#
# migrationCompanion demo — Apache Solr 9.x → OpenSearch 3.x
#
# End-to-end: stands up a kind cluster with MA + target OS, overlays the
# Solr source via valuesSolrSource.yaml, seeds two canonical Solr
# collections (techproducts, films), then hands control to the companion
# agent (loaded as a Kiro agent via install-skill.sh).
#
# Flags:
#   --interactive   Drop into interactive kiro-cli chat after setup.
#   --autopilot     (default) Kiro runs end-to-end non-interactively.
#   --skip-setup    Assume clusters are already up; just run the agent.
#
# Idempotent. Wall-clock: ~20-30 min cold, ~4-6 min warm.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="kind-${KIND_CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-ma}"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo}"

SOURCE_VERSION="${SOURCE_VERSION:-9.7.0}"
TARGET_VERSION="${TARGET_VERSION:-3.1.0}"
SOLR_FULLNAME="solr-source"                       # from testClusters values.yaml
SOLR_SVC="${SOLR_FULLNAME}-solrcloud-common"      # solr-operator convention
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
  say "Bringing up kind cluster '${KIND_CLUSTER_NAME}' with MA + default (ES+OS) test clusters"
  cd "${REPO_ROOT}"
  bash deployment/k8s/kindTesting.sh

  say "Overlaying Solr source onto the tc release (disables ES source, keeps OS target at ${TARGET_VERSION})"
  helm --kube-context "${KIND_CONTEXT}" upgrade tc \
    "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters" \
    -n "${NAMESPACE}" --reuse-values --wait --timeout 15m \
    -f "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters/valuesSolrSource.yaml" \
    --set "solrSource.image.tag=${SOURCE_VERSION}" \
    --set "target.image.tag=${TARGET_VERSION}"

  say "Waiting for SolrCloud + OS pods to be Ready"
  # SolrCloud resource is managed by the Solr operator; the solrcloud-common
  # service is only created once the statefulset has at least one Ready pod.
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready pod \
    -l "solr-cloud=${SOLR_FULLNAME}" --timeout=900s
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready pod \
    -l 'app=opensearch-cluster-master' --timeout=600s

  say "Starting port-forwards (Solr source -> 18983, OS target -> 19201)"
  pkill -f "port-forward.*${SOLR_SVC}"                2>/dev/null || true
  pkill -f "port-forward.*opensearch-cluster-master"  2>/dev/null || true
  sleep 1
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "svc/${SOLR_SVC}" 18983:80 >/tmp/pf-solr.log 2>&1 &
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward svc/opensearch-cluster-master 19201:9200 >/tmp/pf-target.log 2>&1 &

  for i in $(seq 1 60); do
    if curl -sf "http://localhost:18983/solr/admin/info/system" >/dev/null 2>&1 \
       && curl -sk -u admin:admin "https://localhost:19201" >/dev/null 2>&1; then
      ok "Port-forwards live"
      break
    fi
    sleep 2
  done

  SOLR="http://localhost:18983/solr"

  say "Seeding Solr with fixture collections (techproducts, films)"
  # techproducts — the canonical Solr demo corpus, here synthesized small
  curl -sf "${SOLR}/admin/collections?action=CREATE&name=techproducts&numShards=1&replicationFactor=1&wt=json" >/dev/null \
    || warn "techproducts may already exist; continuing"
  curl -sf -H 'Content-Type: application/json' \
    -d '{"add-field":[
          {"name":"manu",   "type":"string", "stored":true, "indexed":true},
          {"name":"cat",    "type":"strings","stored":true, "indexed":true},
          {"name":"features","type":"text_general","stored":true,"indexed":true},
          {"name":"price",  "type":"pfloat", "stored":true, "indexed":true},
          {"name":"inStock","type":"boolean","stored":true, "indexed":true}
        ]}' \
    "${SOLR}/techproducts/schema" >/dev/null 2>&1 || true

  TP_DOCS='['
  for i in $(seq 1 30); do
    TP_DOCS+="{\"id\":\"TP-${i}\",\"name\":\"Gadget ${i}\",\"manu\":\"Acme\",\"cat\":[\"electronics\",\"accessory\"],\"features\":\"fast durable premium unit ${i}\",\"price\":$((20 + RANDOM % 400)).99,\"inStock\":true},"
  done
  TP_DOCS="${TP_DOCS%,}]"
  curl -sf -H 'Content-Type: application/json' \
    -d "${TP_DOCS}" \
    "${SOLR}/techproducts/update?commit=true" >/dev/null

  # films — classic Solr tutorial dataset
  curl -sf "${SOLR}/admin/collections?action=CREATE&name=films&numShards=1&replicationFactor=1&wt=json" >/dev/null \
    || warn "films may already exist; continuing"
  curl -sf -H 'Content-Type: application/json' \
    -d '{"add-field":[
          {"name":"directed_by","type":"strings","stored":true,"indexed":true},
          {"name":"genre",      "type":"strings","stored":true,"indexed":true},
          {"name":"initial_release_date","type":"pdate","stored":true,"indexed":true}
        ]}' \
    "${SOLR}/films/schema" >/dev/null 2>&1 || true

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

# Ensure the companion agent is registered.
if [[ ! -f "${HOME}/.kiro/agents/migration-companion.json" ]]; then
  say "Installing companion skill as Kiro agent"
  bash "${REPO_ROOT}/migrationCompanion/demo/install-skill.sh"
fi

# Pre-create target creds (Solr demo has no auth; target OS uses admin/admin).
say "Ensuring demo secrets exist in namespace ${NAMESPACE}"
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" create secret generic target-creds \
  --from-literal=username=admin --from-literal=password=admin \
  --dry-run=client -o yaml | kubectl --context "${KIND_CONTEXT}" apply -f - >/dev/null
ok "Secrets present"

say "Handoff to companion agent"

read -r -d '' PROMPT <<EOF || true
Source:  Solr ${SOURCE_VERSION}          http://localhost:18983  (no auth)
         In-cluster: http://${SOLR_SVC}.${NAMESPACE}.svc.cluster.local:80
Target:  OpenSearch ${TARGET_VERSION}    https://localhost:19201 (basic, target-creds)
Target uses self-signed TLS; set allowInsecure: true on the target cluster.
Scope:   all non-system collections (techproducts, films).
Mode:    snapshot-only, skipApprovals: true (demo).
Output:  ${REPO_ROOT}/migrationCompanion/runs/<timestamp>/

Drive the migration end-to-end per SKILL.md:
  Phase 0 — schema refresh (cat /root/.workflowUser.schema.json inside pod)
  Phase 1 — probe Solr (/solr/admin/collections?action=LIST, schema, luke)
            and target OS via curl. Skip the interview for these already-
            confirmed fields; do still pick deep-validate collections.
  Phase 2 — scaffold config.yaml against the live schema. Source version
            MUST be literal "SOLR ${SOURCE_VERSION}" (uppercase SOLR).
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
