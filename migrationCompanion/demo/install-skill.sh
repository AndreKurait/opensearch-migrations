#!/usr/bin/env bash
#
# migrationCompanion demo — install companion as a Kiro agent.
#
# The agent loads the entire migrationCompanion/ directory (SKILL.md +
# steering/ + references/) as file:// resources so every turn has the
# full steering context.
#
# Idempotent — overwrites the agent file on each run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AGENT_DIR="${HOME}/.kiro/agents"
AGENT_FILE="${AGENT_DIR}/migration-companion.json"
SKILL_DIR="${REPO_ROOT}/migrationCompanion"

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

command -v kiro-cli >/dev/null 2>&1 || { echo "kiro-cli not found on PATH" >&2; exit 1; }
kiro-cli whoami >/dev/null 2>&1       || { echo "kiro-cli is not logged in. Run: kiro-cli login" >&2; exit 1; }
command -v jq    >/dev/null 2>&1      || { echo "jq not found" >&2; exit 1; }

mkdir -p "${AGENT_DIR}"

# All the skill files get loaded into the agent's context every turn.
RESOURCES=(
  "file://${SKILL_DIR}/SKILL.md"
  "file://${SKILL_DIR}/steering/00-schema-refresh.md"
  "file://${SKILL_DIR}/steering/01-interview-probe.md"
  "file://${SKILL_DIR}/steering/02-scaffold-config.md"
  "file://${SKILL_DIR}/steering/03-secrets-submit.md"
  "file://${SKILL_DIR}/steering/04-validate-parity-relevancy.md"
  "file://${SKILL_DIR}/steering/05-report.md"
  "file://${SKILL_DIR}/steering/06-shim-analysis.md"
  "file://${SKILL_DIR}/steering/99-pitfalls.md"
  "file://${SKILL_DIR}/references/ma-workflow-cli.md"
  "file://${REPO_ROOT}/kiro-cli/kiro-cli-config/steering/workflow.md"
)

# Short invocation prompt — full rules live in SKILL.md.
read -r -d '' PROMPT <<'PROMPT' || true
You are the Migration Companion. Your behavior is entirely defined by the
attached SKILL.md and the steering/ files. Load SKILL.md first, then
proceed to steering/00-schema-refresh.md. Checkpoint with the user
before each destructive or long-running step (submit, secret creation,
approvals). Redact credentials. Never hand-write Argo workflow YAML.
PROMPT

printf '%s\n' "${RESOURCES[@]}" | jq -R . | jq -s \
  --arg prompt "${PROMPT}" \
  '{
    name: "migration-companion",
    description: "Unified Solr/Elasticsearch/OpenSearch → OpenSearch migration companion. Drives workflow CLI empirically on a local kind cluster; produces parity + relevancy reports.",
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
  Steering installed
────────────────────────────────────────────────────────────────
  Agent file: ${AGENT_FILE}
  Resources:  ${#RESOURCES[@]} files from migrationCompanion/

  The agent now has SKILL.md + all steering/ + references/ loaded.

  Next: run one of
    bash migrationCompanion/demo/es-to-os.sh
────────────────────────────────────────────────────────────────
EOF
