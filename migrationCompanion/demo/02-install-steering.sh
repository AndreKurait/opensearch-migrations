#!/usr/bin/env bash
#
# migrationCompanion demo — Step 2: install the companion steering docs as a
# Kiro CLI agent so `kiro-cli chat` (and ACP delegations) reason with them.
#
# What this installs:
#   - ~/.kiro/agents/migration-companion.json  (agent definition)
#   - Steering resource set: README, plan schema, scripts, plans/ docs,
#     all served to Kiro as `file://` resources so they're loaded into
#     the agent's context on every turn.
#
# Idempotent. Re-running overwrites the agent file.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AGENT_DIR="${HOME}/.kiro/agents"
AGENT_FILE="${AGENT_DIR}/migration-companion.json"

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

command -v kiro-cli >/dev/null 2>&1 || { echo "kiro-cli not found on PATH" >&2; exit 1; }
kiro-cli whoami >/dev/null 2>&1 || { echo "kiro-cli is not logged in. Run: kiro-cli login" >&2; exit 1; }

mkdir -p "${AGENT_DIR}"

# Resources = steering docs Kiro loads every turn.
# Paths are absolute file:// URIs so the agent works from any cwd.
RESOURCES=(
  "file://${REPO_ROOT}/migrationCompanion/README.md"
  "file://${REPO_ROOT}/migrationCompanion/schemas/migration-plan.schema.json"
  "file://${REPO_ROOT}/migrationCompanion/examples/es7-to-os3-plan.json"
  "file://${REPO_ROOT}/migrationCompanion/examples/minimal-plan.json"
  "file://${REPO_ROOT}/migrationCompanion/scripts/probe_source.py"
  "file://${REPO_ROOT}/migrationCompanion/scripts/emit_workflow.py"
  "file://${REPO_ROOT}/migrationCompanion/scripts/validate_plan.py"
  "file://${REPO_ROOT}/migrationCompanion/scripts/run_empirical.py"
  "file://${REPO_ROOT}/migrationCompanion/scripts/parity_check.py"
  "file://${REPO_ROOT}/docs/plans/2026-04-30-migration-companion-unified-ux.md"
  "file://${REPO_ROOT}/docs/plans/2026-05-01-iteration-0-field-report.md"
)

# Build the JSON with jq so we don't hand-craft escapes.
printf '%s\n' "${RESOURCES[@]}" | jq -R . | jq -s \
  --arg prompt "$(cat <<'PROMPT'
You are the Migration Companion — a unified guide for migrating data into
OpenSearch from Elasticsearch, self-managed OpenSearch, or Apache Solr,
using the Migration Assistant (MA) project.

How you work:
  1. Ask the user for source + target cluster endpoints and credentials
     (3 questions max — infer everything else).
  2. Probe the source and target with scripts/probe_source.py.
  3. Generate a migration-plan.json that conforms to
     schemas/migration-plan.schema.json. Prefer createSnapshotConfig
     (MA creates the snapshot); use BYOS only when the user explicitly
     has an existing snapshot, a huge snapshot, or an air-gapped source.
  4. Validate the plan with scripts/validate_plan.py.
  5. Emit an Argo workflow with scripts/emit_workflow.py and submit it
     via scripts/run_empirical.py (which also handles preflight cleanup
     of stale snapshotmigrations/approvalgates CRs — MA hardcodes the
     snapshot key to "testsnapshot" so stale CRs block re-runs).
  6. Run a parity check with scripts/parity_check.py and produce a
     human report + machine parity.json.

Hard constraints:
  - NEVER reference Elasticsearch source in commits, PR text, or
    customer-facing docs — upstream project license rules.
  - Basic-auth creds should be redacted in reports.
  - ES port-forward is HTTPS not HTTP; always use `curl -sk https://…`.
  - Target OS 3.x via helm chart default; iter-1 used 2.11.1.
  - Treat migration-plan.json as a versioned first-class artifact.
  - Parity bar: top-K overlap + error-free execution; don't invent
    stricter diffs unless the user asks.

Tone: concise, empirical, no speculation. If you don't know, probe.
PROMPT
)" \
  '{
    name: "migration-companion",
    description: "Migration Companion — unified Solr/ES/OS → OpenSearch migration guide backed by empirical kind-based dry runs.",
    prompt: $prompt,
    tools: ["read","write","shell","grep","glob","thinking","todo"],
    allowedTools: [],
    mcpServers: {},
    resources: .,
    toolsSettings: {}
  }' > "${AGENT_FILE}"

say "Validating agent config"
kiro-cli agent validate --path "${AGENT_FILE}"

ok "Installed Kiro agent: migration-companion"
cat <<EOF

────────────────────────────────────────────────────────────────
  Steering docs installed
────────────────────────────────────────────────────────────────
  Agent file: ${AGENT_FILE}
  Resources:  ${#RESOURCES[@]} files from migrationCompanion/

  Next step:
    bash migrationCompanion/demo/03-run-companion.sh
────────────────────────────────────────────────────────────────
EOF
