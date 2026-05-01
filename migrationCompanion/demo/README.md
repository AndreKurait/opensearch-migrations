# migrationCompanion — Demo runner

Three scripts. Run in order. Each is idempotent.

## Prerequisites
- docker, kind, kubectl, helm, jq, curl, python3
- kiro-cli installed and logged in (`kiro-cli login`)
- ~16 GB RAM free for the kind cluster

## 1. Bring up the cluster
```
bash migrationCompanion/demo/01-setup-cluster.sh
```
Creates a kind cluster named `ma`, deploys Migration Assistant + Argo,
stands up an Elasticsearch 8.5.1 source and an OpenSearch 2.x target,
seeds the source with three synthetic indices (`products`,
`legacy_orders`, `events-2025.05`), and opens port-forwards on
`localhost:19200` (source) and `localhost:19201` (target).

Cold run: ~15–25 min (image builds). Warm re-run: ~3–5 min.

## 2. Install the steering docs as a Kiro agent
```
bash migrationCompanion/demo/02-install-steering.sh
```
Writes `~/.kiro/agents/migration-companion.json` that pulls in the
companion README, plan schema, example plans, helper scripts, and
design plans as `file://` resources — so every Kiro turn is grounded
in them.

## 3. Run the companion
```
bash migrationCompanion/demo/03-run-companion.sh              # autopilot (default)
bash migrationCompanion/demo/03-run-companion.sh --interactive  # chat session
```
Kiro probes both clusters, drafts `plan.json`, validates it, submits an
Argo workflow, waits for it to finish, runs a parity check, and writes
`report.md`. Artifacts land in `/tmp/companion-demo/`.

## Tear down
```
kind delete cluster --name ma
```
