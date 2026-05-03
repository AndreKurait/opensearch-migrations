# migrationCompanion demos

End-to-end demos that stand up a local kind cluster with Migration
Assistant (MA) + a source cluster + a target OpenSearch cluster, then
hand control to the companion agent.

## Prerequisites

- docker, kind, kubectl, helm, jq, curl
- kiro-cli (`kiro-cli whoami` must succeed)
- ~10 GB free disk for the cold-start image builds

## Start here

```bash
# One-off: register the companion skill as a Kiro agent.
bash migrationCompanion/demo/install-skill.sh

# End-to-end Elasticsearch 7.10 → OpenSearch 3.1 demo.
bash migrationCompanion/demo/es-to-os.sh

# End-to-end SolrCloud 9.7 → OpenSearch 3.5 demo (full workflow path).
bash migrationCompanion/demo/solr-backfill.sh
```

Both scripts are idempotent — re-run to iterate on the skill.
Artifacts land under `migrationCompanion/runs/<timestamp>/`.

## Scripts

| Script                 | Purpose                                          |
|------------------------|--------------------------------------------------|
| `00-reset.sh`          | Wipe kind cluster, port-forwards, runs, agent.   |
| `install-skill.sh`     | Register companion dir as Kiro agent.            |
| `es-to-os.sh`          | ES 7.10 → OS 3.5 end-to-end on kind.             |
| `solr-backfill.sh`     | SolrCloud 9.7 → OS 3.5 end-to-end on kind.       |
| `solr-to-os.sh`        | SolrCloud → OS via the translation-shim sandbox  |
|                        | (docker compose, not the full workflow path).    |

The two Solr demos exercise different slices:

- `solr-backfill.sh` drives the **full orchestrator workflow**
  (CreateSnapshot with `--source-type=solr` auto-detected, metadata
  migration, RFS document backfill, validation) on kind.
- `solr-to-os.sh` exercises the **translation-shim** in isolation —
  the request-rewriting proxy that lets OpenSearch query engines
  answer Solr-shaped traffic. It uses the docker-compose sandbox in
  `solrMigrationDevSandbox/` and does not go through `workflow`.

## Modes

All demo drivers accept:

- `--autopilot` (default) — Kiro runs non-interactively to completion.
- `--interactive` — drop into a chat session after setup.
- `--skip-setup` — assume clusters already up; just invoke the agent.

## Troubleshooting

- **Source not reachable on 19200**: a port-forward died. Re-run the
  demo or re-run just the port-forward block manually.
- **Secret already exists**: the demo creates `source-creds` and
  `target-creds` pre-populated with the OS demo installer's admin password
  (`admin:myStrongPassword123!`; `source-creds` for ES remains `admin:admin`).
  `00-reset.sh` wipes
  them with the cluster.
- **Workflow stuck at WAITING**: `skipApprovals: true` is set in the
  demo seed prompt, but the agent may still hit an approval if the
  schema version changed. Run `kiro-cli chat --agent migration-companion
  --trust-all-tools "Resume. Check workflow status and approve if the
  output looks correct."`
- **Kiro agent not found**: re-run `install-skill.sh`.

## What gets created

```
/tmp/companion-demo/                  (scratch — port-forward logs)
migrationCompanion/runs/<UTC-ts>/     (per-run artifacts, gitignored)
  ├── config.yaml
  ├── schema.json + schema.sha256
  ├── probe-source.json
  ├── probe-target-before.json
  ├── probe-target-after.json
  ├── queries/q*.{source,target,summary}.json
  └── report.md
~/.kiro/agents/migration-companion.json
```
