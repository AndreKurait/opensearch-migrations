# Migration Companion

**Migration Companion is a skill, not a binary.** An AI agent (Claude,
Kiro, Cursor, Hermes, etc.) loads this directory and guides a user
end-to-end through an OpenSearch migration — from Elasticsearch,
OpenSearch, or Solr — using nothing but `kubectl`, `curl`, `jq`, and
the migration-console pod's `workflow` CLI.

There is no `mc` CLI to install. No Python helpers. No templates. The
agent *is* the tool.

## What it does

Given a source cluster and a target OpenSearch cluster, the companion:

1. **Reads the live JSON Schema** at
   `/root/schema/workflowMigration.schema.json` inside the migration-console pod.
   Every run. No stale training-data assumptions.
2. **Probes both clusters** with `curl` — versions, field inventories,
   real sample documents, analyzer behavior.
3. **Scaffolds a `config.yaml`** the user sends to the pod via
   `workflow configure edit --stdin`, validates it, and submits.
4. **Watches the workflow**, surfacing phase transitions and pausing
   for approval gates.
5. **Validates the target** in three layers: structural parity (doc
   counts, mappings), a query-shape battery (5–10 queries across field
   types), and a relevancy showcase (2–3 natural-language queries with
   side-by-side top-5 tables and hypothesis narrative).
6. **Writes a self-contained `report.md`** — human-readable summary
   above, complete reproduction commands below — into
   `migrationCompanion/runs/<timestamp>/`.

Unified path: Solr, Elasticsearch, and self-managed OpenSearch sources
all converge on the same `workflow` configure → submit → validate →
report flow.

## Layout

```
migrationCompanion/
├── README.md                  you are here
├── SKILL.md                   agent entrypoint — rules + phase map
├── steering/
│   ├── 00-schema-refresh.md            pull live JSON Schema
│   ├── 01-interview-probe.md           probe source & target
│   ├── 02-scaffold-config.md           build config.yaml
│   ├── 03-secrets-submit.md            submit + watch
│   ├── 04-validate-parity-relevancy.md structural + queries + showcase
│   ├── 05-report.md                    report.md contract
│   └── 99-pitfalls.md                  landmines + expected drifts
├── references/
│   └── ma-workflow-cli.md              quick CLI reference
├── runs/                        per-run artifacts (gitignored)
└── demo/                        local kind-based end-to-end demos
    ├── 00-reset.sh              wipe everything
    ├── install-skill.sh         register companion as Kiro agent
    ├── es-to-os.sh              Elasticsearch 7.x → OpenSearch 3.x
    ├── solr-to-os.sh            Apache Solr 8 → OpenSearch 3.x
    └── solr8-standalone.yaml    single-pod Solr 8 k8s manifest
```

## Who uses this

The companion is authored as a **skill directory**. Any agent that can
follow markdown instructions and exec `kubectl`/`curl` can drive it.
The bundled demo registers it as a Kiro CLI agent so you can do:

```bash
bash migrationCompanion/demo/install-skill.sh
kiro-cli chat --agent migration-companion --trust-all-tools \
  "Migrate ES 7.10 at https://src:9200 to OS 3.1 at https://tgt:9200."
```

Any other agent runtime (Hermes skill loader, Cursor MDC, Claude
Projects file upload) can load the same SKILL.md + steering/ and get
the same behavior.

## Design principles

- **The JSON Schema is the truth.** Not training data. Not samples.
  Not this README. Agents re-read it every run and record a sha256.
- **No helper scripts.** Every step is something an attentive human
  could do with a terminal and judgment. The agent supplies the
  judgment.
- **Checkpoint before destructive steps.** Submit, secret creation, and
  approval gates all require explicit user confirmation. Non-
  interactive demos log what they would have asked.
- **Parity is a verdict, not a threshold.** The agent reports raw
  numbers and its own judgment ("top-10 overlap 9/10 — noise given
  analyzer parity"). The reader can overrule.

## Demos

- `demo/es-to-os.sh` — Elasticsearch 7.10 → OpenSearch 3.5 end-to-end on
  a local kind cluster.
- `demo/solr-to-os.sh` — Apache Solr 8.11.4 → OpenSearch 3.5 end-to-end on
  a local kind cluster. Deploys a single-pod SolrCloud (embedded ZK, plain
  k8s Deployment — no solr-operator) alongside MA, seeds the canonical
  `techproducts` and `films` collections, and exercises the skill's Solr
  probe and Solr→OpenSearch query-translation guidance. Uses the same
  Solr-8 image + `solr.xml` the repo's own integration tests use.

## Related

- Workflow CLI command reference: `kiro-cli/kiro-cli-config/steering/workflow.md`
- Workflow user schema source: `orchestrationSpecs/packages/schemas/src/userSchemas.ts`
- Runtime schema (read every run): `/root/schema/workflowMigration.schema.json` on the
  migration-console pod.
