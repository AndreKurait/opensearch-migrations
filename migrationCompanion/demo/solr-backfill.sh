#!/usr/bin/env bash
#
# migrationCompanion demo — SolrCloud 9.x → OpenSearch 3.x (snapshot backfill)
#
# End-to-end: stands up a kind cluster with MA + SolrCloud source + OS target,
# seeds the source with a small fixture collection, then hands control to
# the companion agent (loaded as a Kiro agent via install-skill.sh).
#
# Unlike the shim-sandbox demo (solr-to-os.sh), this path exercises the
# full orchestrator workflow: CreateSnapshot (--source-type=solr
# auto-detected) → metadata migration → RFS document backfill → validation.
#
# Flags:
#   --interactive   Drop into interactive kiro-cli chat after setup.
#   --autopilot     (default) Kiro runs end-to-end non-interactively.
#   --skip-setup    Assume clusters are already up; just run the agent.
#   --source-version X.Y.Z
#                   Label only; the deployed Solr image tag lives in
#                   valuesSolrSource.yaml (currently 9.7.0).
#
# Idempotent. Wall-clock: ~20-30 min cold, ~5-8 min warm.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="kind-${KIND_CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-ma}"
OUT_DIR="${OUT_DIR:-/tmp/companion-demo-solr}"

SOURCE_VERSION="${SOURCE_VERSION:-9.7.0}"
# Target OpenSearch version is owned by testClusters/valuesCompanionDemo.yaml.
# Keep a string here only for labelling the status banner.
TARGET_VERSION="3.5.0"
COLLECTION="${COLLECTION:-companion_demo}"
MODE="autopilot"
SKIP_SETUP="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interactive) MODE="interactive"; shift ;;
    --autopilot)   MODE="autopilot";   shift ;;
    --skip-setup)  SKIP_SETUP="true";  shift ;;
    --source-version) SOURCE_VERSION="$2"; shift 2 ;;
    --collection)     COLLECTION="$2"; shift 2 ;;
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

SOLR_SVC="solr-source-solrcloud-common"
OS_SVC="opensearch-cluster-master"

if [[ "${SKIP_SETUP}" != "true" ]]; then
  say "Bringing up kind cluster '${KIND_CLUSTER_NAME}' with MA + default test clusters"
  cd "${REPO_ROOT}"
  bash deployment/k8s/kindTesting.sh

  # kindTesting.sh returns before the Kyverno admission webhook has
  # endpoints — wait it out so the next helm upgrade doesn't race.
  say "Waiting for Kyverno admission controller (kyverno-ma ns) to be Ready"
  kubectl --context "${KIND_CONTEXT}" -n kyverno-ma rollout status \
    deploy/kyverno-admission-controller --timeout=300s || true
  kubectl --context "${KIND_CONTEXT}" -n kyverno-ma wait \
    --for=condition=Available deploy/kyverno-admission-controller \
    --timeout=300s || true
  for _ in $(seq 1 60); do
    eps=$(kubectl --context "${KIND_CONTEXT}" -n kyverno-ma get endpoints \
      kyverno-svc -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)
    [ -n "${eps}" ] && break
    sleep 2
  done

  say "Swapping test-clusters to Solr source (valuesSolrSource.yaml) + OS ${TARGET_VERSION} target"
  # Solr Operator CRDs ship separately from the helm chart (upstream
  # convention), so apply them before the chart tries to create the
  # SolrCloud CR. Version must match solr-operator subchart version
  # in charts/aggregates/testClusters/Chart.yaml.
  say "Applying Solr Operator CRDs (v0.9.1)"
  kubectl --context "${KIND_CONTEXT}" apply --server-side -f \
    https://solr.apache.org/operator/downloads/crds/v0.9.1/all-with-dependencies.yaml
  # The operator bundle also ships the zookeeper CRD, which the helm
  # subchart claims ownership of. Hand that one back to helm so the
  # upgrade can proceed without an ownership-conflict error.
  kubectl --context "${KIND_CONTEXT}" label crd \
    zookeeperclusters.zookeeper.pravega.io \
    app.kubernetes.io/managed-by=Helm --overwrite >/dev/null || true
  kubectl --context "${KIND_CONTEXT}" annotate crd \
    zookeeperclusters.zookeeper.pravega.io \
    meta.helm.sh/release-name=tc \
    meta.helm.sh/release-namespace="${NAMESPACE}" \
    --overwrite >/dev/null || true
  # --reuse-values keeps the image overrides that kindTesting.sh baked in
  # via --set (migrations/* repositories), and layers the Solr source +
  # OS version bumps on top.
  helm --kube-context "${KIND_CONTEXT}" upgrade tc \
    "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters" \
    -n "${NAMESPACE}" --reuse-values --wait --timeout 10m \
    -f "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters/valuesSolrSource.yaml" \
    -f "${REPO_ROOT}/deployment/k8s/charts/aggregates/testClusters/valuesCompanionDemo.yaml"

  # The Adobe zookeeper-operator fork (pinned in valuesCompanionDemo.yaml
  # as the workaround for the pravega 0.2.15 lfstack panic) uses leader
  # election, which needs `coordination.k8s.io/leases`. The 0.9.1 chart's
  # Role predates that requirement, so patch in the verbs post-install.
  say "Patching zookeeper-operator Role with coordination.k8s.io/leases verbs"
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" patch role \
    tc-zookeeper-operator --type=json \
    -p='[{"op":"add","path":"/rules/-","value":{"apiGroups":["coordination.k8s.io"],"resources":["leases"],"verbs":["get","list","watch","create","update","patch","delete"]}}]' \
    >/dev/null 2>&1 || true
  # Restart the operator so it picks up the new permission and starts
  # reconciling the ZookeeperCluster CR.
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" rollout restart \
    deploy/tc-zookeeper-operator >/dev/null

  say "Waiting for SolrCloud + OpenSearch pods to be Ready"
  # The Solr Operator creates the StatefulSet asynchronously; poll until
  # at least one solrcloud pod label exists, then wait for readiness.
  for _ in $(seq 1 60); do
    if kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
        get pods -l technology=solr-cloud 2>/dev/null | grep -q solr-source; then
      break
    fi
    sleep 5
  done
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready pod \
    -l 'technology=solr-cloud' --timeout=600s
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready pod \
    -l 'app=opensearch-cluster-master' --timeout=600s

  say "Starting port-forwards (Solr -> 18983, OpenSearch -> 19201)"
  pkill -f "port-forward.*${SOLR_SVC}" 2>/dev/null || true
  pkill -f "port-forward.*${OS_SVC}"   2>/dev/null || true
  sleep 1
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "svc/${SOLR_SVC}" 18983:80 >/tmp/pf-solr.log 2>&1 &
  kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "svc/${OS_SVC}" 19201:9200 >/tmp/pf-os.log 2>&1 &

  # Solr is plain HTTP (no auth); OS target keeps its security plugin from
  # valuesCompanionDemo baseline, so we still need https + -u admin:....
  for i in $(seq 1 30); do
    if curl -sf http://localhost:18983/solr/admin/info/system >/dev/null 2>&1 \
       && curl -sk -u 'admin:myStrongPassword123!' https://localhost:19201 >/dev/null 2>&1; then
      ok "Port-forwards live"
      break
    fi
    sleep 2
  done

  say "Creating SolrCloud collection '${COLLECTION}' (1 shard, 1 replica)"
  SOLR="http://localhost:18983"
  # Idempotent: delete first, ignore failure (collection may not exist yet).
  curl -sf "${SOLR}/solr/admin/collections?action=DELETE&name=${COLLECTION}" >/dev/null 2>&1 || true
  CREATE_HTTP=$(curl -s -o /tmp/solr-create.json -w '%{http_code}' \
    "${SOLR}/solr/admin/collections?action=CREATE&name=${COLLECTION}&numShards=1&replicationFactor=1&wt=json")
  if [[ "${CREATE_HTTP}" != "200" ]]; then
    warn "Solr CREATE returned HTTP ${CREATE_HTTP}; body:"
    cat /tmp/solr-create.json >&2 || true
    exit 1
  fi
  # Wait for collection to be active before seeding.
  for _ in $(seq 1 30); do
    if curl -sf "${SOLR}/solr/${COLLECTION}/admin/ping" >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done

  say "Seeding ${COLLECTION} with 100 fixture docs"
  # Generate 100 products-style docs as a single JSON array and POST to
  # /update/json/docs with commit=true.
  DOCS=$(jq -c -n --argjson n 100 '
    [range(0; $n) | {
      id: ("P-" + (. | tostring)),
      name: ("Widget " + (. | tostring)),
      sku: ("SKU-" + (. | tostring)),
      price: (. * 7 % 500),
      in_stock: ((. % 3) != 0),
      category: (["tools","electronics","outdoor","kitchen"][. % 4]),
      description: ("A high-quality item number " + (. | tostring) + " for demo purposes.")
    }]')
  POST_HTTP=$(curl -s -o /tmp/solr-post.json -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -XPOST "${SOLR}/solr/${COLLECTION}/update/json/docs?commit=true" \
    --data "${DOCS}")
  if [[ "${POST_HTTP}" != "200" ]]; then
    warn "Solr POST returned HTTP ${POST_HTTP}; body:"
    cat /tmp/solr-post.json >&2 || true
    exit 1
  fi

  ok "Source seeded:"
  NUM=$(curl -sf "${SOLR}/solr/${COLLECTION}/select?q=*:*&rows=0&wt=json" | jq -r '.response.numFound')
  echo "  numFound = ${NUM} (expected 100)"
fi

# Ensure the companion agent is registered.
if [[ ! -f "${HOME}/.kiro/agents/migration-companion.json" ]]; then
  say "Installing companion skill as Kiro agent"
  bash "${REPO_ROOT}/migrationCompanion/demo/install-skill.sh"
fi

# Pre-create source-creds / target-creds secrets so the agent doesn't
# have to interview for them. Solr source in this demo has NO auth, but
# the schema still allows (and the config may still reference) an
# authConfig block. For a no-auth Solr source, the companion should
# OMIT authConfig on sourceClusters.source entirely. We still create
# `source-creds` as an empty placeholder to simplify the scaffold path.
say "Ensuring demo secrets exist in namespace ${NAMESPACE}"
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" create secret generic source-creds \
  --from-literal=username= --from-literal=password= \
  --dry-run=client -o yaml | kubectl --context "${KIND_CONTEXT}" apply -f - >/dev/null
kubectl --context "${KIND_CONTEXT}" -n "${NAMESPACE}" create secret generic target-creds \
  --from-literal=username=admin --from-literal='password=myStrongPassword123!' \
  --dry-run=client -o yaml | kubectl --context "${KIND_CONTEXT}" apply -f - >/dev/null
ok "Secrets present"

say "Handoff to companion agent"

# Hints for the agent:
#  - Source is plain HTTP with no auth → omit authConfig on sourceClusters.source
#  - Target is HTTPS self-signed with basic auth → keep allowInsecure: true,
#    authConfig.basic.secretName: target-creds
#  - Backup repository on Solr side is pre-wired to localstack via
#    valuesSolrSource.yaml (name: localstack-s3, bucket: solr-backups)
read -r -d '' PROMPT <<EOF || true
Source:  SolrCloud ${SOURCE_VERSION}  http://localhost:18983  (no auth)
         Collection: ${COLLECTION} (100 docs seeded)
         Backup repo pre-registered: name=localstack-s3, bucket=solr-backups
Target:  OpenSearch ${TARGET_VERSION}  https://localhost:19201  (basic, target-creds)
         Self-signed TLS; set allowInsecure: true on target.
Scope:   the ${COLLECTION} collection (whole collection, not a subset).
Mode:    snapshot + document backfill, skipApprovals: true (demo).
Output:  ${REPO_ROOT}/migrationCompanion/runs/<timestamp>/

Drive the migration end-to-end per SKILL.md:
  Phase 0 — schema refresh (cat /root/schema/workflowMigration.schema.json inside pod)
  Phase 1 — probe Solr via curl (use the Solr probe section of
            steering/01-interview-probe.md). Confirm version as
            "SOLR ${SOURCE_VERSION}" from /solr/admin/info/system.
            Then probe the OS target as usual.
  Phase 2 — scaffold config.yaml. The common case for Solr needs NO
            Solr-specific fields (see steering/02-scaffold-config.md
            "Solr sources"). Use:
              snapshotInfo.repos.repo1.s3RepoPathUri: s3://solr-backups/${COLLECTION}
              snapshotInfo.repos.repo1.awsRegion: us-east-1
              snapshotInfo.snapshots.snap1.config.createSnapshotConfig: {}
            Omit authConfig on sourceClusters.source (no-auth Solr).
  Phase 3 — submit via workflow submit, watch.
  Phase 4/5 — structural parity (collection count → index doc count +
              a representative mapping spot-check), 5-8 query-shape
              tests, 2 relevancy showcase queries you pick from the
              Solr probe.
  Phase 6 — write report.md to the run dir.

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
      "${PROMPT}"
    KIRO_EXIT=$?
    echo "--- end companion session (kiro-cli exit=${KIRO_EXIT}) ---"

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
