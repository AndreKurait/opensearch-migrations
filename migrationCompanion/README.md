# Migration Companion

**Migration Companion is a skill, not a binary.** An AI agent (Claude, Cursor, Hermes, etc.) loads this directory and guides a user end-to-end through an OpenSearch migration — Solr or Elastic — using nothing but `docker`, `kind`, `helm`, `kubectl`, `curl`, and a small kit of deterministic Python helpers shipped here.

There is no `mc` CLI to install. The agent *is* the tool.

## What it does

Given a source cluster and target cluster (or the intent to stand up one), the companion:

1. **Interviews** the user (~6 questions) and produces `migration-plan.json`.
2. **Empirically assesses** the plan by running a real `opensearch-migrations` Argo workflow against a sample of the source on a local `kind` cluster.
3. **Reports** ground-truth errors, parity metrics, time estimates, and expected-benign drifts.
4. **Emits** a validated `workflow.yaml` the user (or the agent) submits to a production cluster.

Unified path: Solr and Elastic/OpenSearch sources both converge on the same `migration-plan.json`, same Argo workflow template (`full-migration-imported-clusters`), same report shape.

## Layout

```
migrationCompanion/
├── README.md                  you are here
├── SKILL.md                   agent instructions (primary entrypoint)
├── steering/                  narrative rules the agent follows
│   ├── interview.md           the ~6-question flow
│   ├── empirical-probe.md     how to drive the kind dry-run
│   ├── parity-rules.md        what counts as match vs benign drift
│   └── pitfalls.md            PF1-PF9 from iter-0 field report
├── schemas/
│   └── migration-plan.schema.json
├── scripts/                   deterministic Python helpers (~500 LOC total)
│   ├── validate_plan.py       lint migration-plan.json
│   ├── probe_source.py        introspect source cluster -> plan draft
│   ├── emit_workflow.py       migration-plan.json -> Argo Workflow YAML
│   ├── run_empirical.py       orchestrates kind probe end-to-end
│   └── parity_check.py        source vs target diff w/ allowlist
├── fixtures/
│   └── es7_sample.json        synthetic fixture for local dry-run
├── references/
│   ├── plan-v3.md             link to docs/plans/*-unified-ux.md
│   └── iter0-report.md        link to docs/plans/*-iteration-0-*.md
└── examples/
    ├── minimal-plan.json
    └── es7-to-os3-plan.json
```

## Quickstart (for an agent)

```
You: "Help me migrate my ES cluster to OpenSearch."

Agent: <loads SKILL.md>
       <asks 6 questions from steering/interview.md>
       <writes migration-plan.json>
       <runs scripts/run_empirical.py against user's kind cluster>
       <shows report.md + workflow.yaml>
```

## Quickstart (for a human verifying the flow)

```
cd migrationCompanion
# Assume you already have a kind cluster 'ma' with MA helm chart deployed.
python3 scripts/run_empirical.py \
  --plan examples/es7-to-os3-plan.json \
  --kubeconfig /tmp/kind-iter0-logs/kubeconfig2 \
  --namespace ma \
  --out /tmp/companion-report
# -> /tmp/companion-report/report.md
# -> /tmp/companion-report/workflow.yaml
# -> /tmp/companion-report/findings.json
```
