#!/usr/bin/env bash
#
# migrationCompanion demo — Apache Solr 9.x → OpenSearch 3.x
#
# STATUS: STUB. The in-tree testClusters aggregate chart does not yet
# include a Solr source. This script documents the intended shape and
# fails fast with guidance.
#
# When Solr-source support lands (see parent plan doc in docs/plans/),
# this script will:
#   - stand up a kind cluster with MA + Solr source + OS target
#   - seed Solr's techproducts collection (the canonical demo corpus)
#   - hand off to the companion agent with a seed prompt below
#
# See es-to-os.sh for the working reference implementation.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

say()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

warn "solr-to-os.sh is a STUB."
cat <<EOF

The migrationCompanion skill fully supports Solr → OpenSearch at the
agent level (see steering/01-interview-probe.md and
steering/04-validate-parity-relevancy.md — both explicitly call out the
Solr probe surface and the Solr→OpenSearch-DSL query translation).

What's missing to make this demo runnable end-to-end:

  1. A Solr Helm chart (or raw manifests) in
     deployment/k8s/charts/components/solr-source/ that exposes:
       - a SolrCloud or standalone pod on port 8983
       - a techproducts-like collection seeded at startup
       - basic auth configured via k8s secret

  2. testClusters aggregate chart wired to deploy solr-source when
     --set source.engine=solr is passed.

  3. The workflow user schema's source cluster entry accepts version
     "SOLR 9.7.0" (already supported per userSchemas.ts regex).

Until then, to exercise the Solr path of the companion:

  - Point the agent at an existing Solr cluster you run locally
    (docker run -p 8983:8983 solr:9.7 solr-precreate techproducts).
  - Install the companion agent:
      bash ${REPO_ROOT}/migrationCompanion/demo/install-skill.sh
  - Kick off a chat:
      kiro-cli chat --agent migration-companion --trust-all-tools \\
        "Migrate Solr 9.7 at http://localhost:8983 (collection techproducts)
         to OpenSearch at https://localhost:19201. Drive end-to-end per
         SKILL.md."

See docs/plans/2026-05-01-migration-companion-agent-first-pivot.md for
the tracked follow-up to lift this stub into a working demo.
EOF
exit 1
