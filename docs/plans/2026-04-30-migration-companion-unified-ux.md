# Migration Companion — Unified UX Plan

Status: **DRAFT v3** for iteration. No implementation in this branch yet.
Refs: #2503, #2444, #2504
Base: `main` @ 8782408b3

---

## The shape of the thing

The Migration Companion is **not a new binary**. There is no `mc` CLI to
build. The companion is:

1. **A skill** — SKILL.md + steering + references that teach any
   ACP-compatible agent (Kiro, Claude Code, Cursor, Cline, etc.) how to
   execute the full migration journey end to end.
2. **A small set of helper scripts** — deterministic utilities the
   agent calls: schema parsers, query-parity diff, plan-json validator,
   report writer. Python, ~500 LOC total.
3. **Native execution via the agent** — the agent runs `docker`,
   `minikube`, `helm`, `kubectl`, `curl`, `workflow submit` directly.
   No wrapper, no daemon, no new orchestrator process.

This matches the "AI-is-the-interface" vision in #2444 and mirrors the
proven pattern already in `kiro-cli/` (agent JSON + prompt + steering
markdown) and `solr-opensearch-migration-advisor/` (SKILL.md + steering
+ helper scripts).

The companion **replaces both**: it generalizes kiro-cli to cover Solr,
and it upgrades the Solr advisor from speculative report to empirical
ground truth.

---

## The core insight

Today's two efforts split the job in half:

- **kiro-cli** orchestrates (runs the real MA workflow) but only speaks
  ES/OS and doesn't assess up-front.
- **Solr advisor** assesses (interviews + writes a report) but only
  speaks Solr and doesn't run anything.

Neither covers the full journey. Unifying naively loses value on one side
or the other. Instead:

> **Make the assessment phase empirical.** The agent spins up a local
> sandbox with the real Migration Assistant in it, performs a real
> end-to-end migration of the source, runs the user's queries against
> the migrated target, and writes a report grounded in what actually
> happened — not what a conversation predicted.

Same skill, one session, multiple phases, agent-driven throughout.

---

## Phases of the unified journey

All phases are agent-executed. "Phase" means a section of the SKILL.md
procedure, not a separate program.

```
Phase 0  Preflight     agent verifies docker/minikube/kubectl/helm,
                       pulls images, warms cache
Phase 1  Advise        agent interviews (intent/constraints/stakes only)
                       + samples source OR ingests user snapshot
                       + spins up minikube sandbox with MA + OS 3.5
                       + deploys MA helm chart, submits REAL workflow
                       + observes migration: errors, warnings, mapping
                         deltas, rejected docs, type coercions
                       + runs user queries against migrated sandbox
                       + writes ground-truth-informed report and
                         migration-plan.json
Phase 2  Human Review  user reads report, edits migration-plan.json
Phase 3  Migrate       agent consumes migration-plan.json, drives real
                       MA against real target (same kiro-cli behavior
                       today, now engine-agnostic, plan-driven)
Phase 4  Verify        agent re-runs query parity vs real target
                       post-migration
```

Phase 0 is a checklist the agent walks through. Phases 1, 3, 4 are the
three main conversational flows. Phase 2 is a human seam — the user
reads files on disk and edits the plan before saying "go."

---

## Phase 1 in detail — the innovation

### Two source-access modes

Both converge on the same sandbox run. The agent picks based on context.

**Live mode** — agent has network access to the source. Reads schemas
directly; drives sandbox MA to pull a snapshot from the live source.
Typical dev/stage/laptop scenario.

**Offline mode** — user supplies an existing snapshot (S3 URI or local
tarball) + schema/query samples. Agent never touches the source network.
Required path for air-gapped/regulated customers.

The MA workflow is snapshot-driven already, so offline mode is just
"skip the create-snapshot step, use this one."

### Sampling — pinned to full migration for v1

No sampling logic in the skill. The sandbox runs the real, full
migration. When the Migration Assistant grows native sampling (separate
workstream), the skill picks it up automatically by using the new
workflow flag.

For large sources, the agent must upfront-estimate run time using the
xlarge-equivalents math already in kiro-cli steering, then present
options: `proceed`, `reduce` to user-picked index allowlist, or
`skip-ground-truth` and fall back to static-analysis-only (clearly
labeled in the report header).

### The sandbox — kind + migrationAssistantWithArgo helm chart

**Default: kind.** More reliable across environments than minikube
(Docker-in-Docker is simpler, no VM layer, faster cold start). Repo's
existing `minikubeLocal.sh` is a useful reference for what the chart
needs (AWS cred mount, image registry) but the companion skill
re-implements the lifecycle against kind. The
`migrationAssistantWithArgo` chart itself is cluster-agnostic — it
installs the same way on kind, minikube, or EKS.

Fallbacks:
- `--sandbox byo` — agent is given an existing empty OS 3.5 cluster +
  kubeconfig (user has their own k8s)
- `--sandbox cloud` — agent provisions a throwaway OS Serverless
  collection + minimal EKS (escape hatch for laptops that can't run
  minikube; v1-stretch)

The sandbox is the agent's responsibility: bring it up, install the
helm chart, wait for health, submit workflow, observe, tear down on
exit unless `--keep-sandbox` was requested.

### Observation — what ground truth means

During the sandbox migration the agent captures, via `kubectl logs`,
`workflow status`, `console clusters curl`, and `workflow output`:

- Per-index metadata outcome: success, failure+reason, silent fallback
- Per-index backfill stats: doc count delta, rejected docs, bulk errors
- Mapping deltas: source schema vs target effective mapping
- Resource events: pod restarts, OOMs, timeouts

Written to `./mc-output/ground-truth/` as structured JSON.
`scripts/support_bundle.py` tarballs this for GitHub issue attachment.

### Query parity — top-K overlap, not exact match

Agent asks the user for sample queries (or ingests a query log).
`scripts/query_parity.py` translates + runs both sides and diffs:

- query translated without error — pass/fail
- query executed without error — pass/fail
- result set non-empty if source was — pass/fail
- top-10 doc ID overlap ≥ 70% — pass/fail (threshold configurable)

Ranking-score parity is explicitly NOT the bar; different similarity
math guarantees drift.

### Interview — aggressive shrinkage

Everything observable gets observed. Interview shrinks to the things
only a human knows:

- What does this cluster do for the business? (drives recommendations)
- Hard constraints? (air-gapped, FIPS, SLA, regulated data)
- Downtime tolerance?
- Prod, stage, or dev?
- Client libraries and front-end integrations that need updating

Six questions, not thirty.

### Phase 1 outputs

```
./mc-output/
├── migration-report.md         human-readable, ground-truth-informed
├── migration-plan.json         machine-readable, Phase-3 input
├── ground-truth/
│   ├── metadata-results.json
│   ├── mapping-deltas.json
│   ├── backfill-errors.jsonl
│   ├── query-parity.json
│   └── resource-events.log
└── sandbox-logs/               full MA logs for support bundle
```

`migration-plan.json` is the Phase 2/3 hand-off contract. Versioned,
schema-validated (extends `userSchemas.ts` in orchestrationSpecs).

---

## Phase 3 — the orchestrator, generalized

Same behavior as today's kiro-cli steering — configure, submit, monitor,
approve — but:

- Engine-agnostic: Solr source paths wire through `SolrReader`
  (already substantial on main)
- Plan-driven: consumes `migration-plan.json` as input instead of
  pure conversational interview
- All destructive AWS/k8s ops still human-gated (preserves today's
  "⛔ NEVER modify without user confirmation" contract)

Interactive-mode entrypoint (`no plan`) still supported for users who
skip Phase 1.

---

## Phase 4 — `verify`

Re-runs the query-parity harness from Phase 1 against the real target.
Nearly free once Phase 1 exists.

---

## Repository layout (proposed)

```
MigrationCompanion/
└── skills/
    └── migration-companion/         ← the unified skill
        ├── SKILL.md                 ← main procedure, all 5 phases
        ├── README.md
        ├── steering/
        │   ├── phase0-preflight.md
        │   ├── phase1-advise.md     ← the big one
        │   ├── phase3-migrate.md    ← generalizes kiro-cli workflow.md
        │   ├── phase4-verify.md
        │   ├── sandbox-lifecycle.md
        │   ├── query-parity.md
        │   ├── ground-truth-capture.md
        │   ├── engine-solr.md       ← Solr-specific commands/gotchas
        │   ├── engine-es-os.md      ← ES/OS-specific
        │   ├── auth-and-access.md
        │   ├── sizing-and-estimates.md
        │   └── safety-gates.md      ← "NEVER modify without confirm"
        ├── references/
        │   ├── solr-schema-migration.md      ← inherited from advisor
        │   ├── solr-query-translation.md     ← inherited
        │   ├── es-os-common-issues.md        ← distilled from kiro-cli
        │   ├── feature-compatibility-matrix.md
        │   └── sizing-reference.md
        ├── scripts/                 ← small, deterministic helpers
        │   ├── solr_schema_to_os_mapping.py  ← from advisor
        │   ├── solr_query_to_os_dsl.py       ← from advisor
        │   ├── query_parity.py               ← NEW
        │   ├── ground_truth_collect.py       ← NEW
        │   ├── plan_json_validate.py         ← NEW
        │   ├── report_render.py              ← from advisor, adapted
        │   └── support_bundle.py             ← NEW
        ├── schemas/
        │   ├── migration-plan.schema.json    ← extends userSchemas.ts
        │   └── ground-truth.schema.json
        └── fixtures/                ← for testing the skill itself
            ├── es-7.10-sample/
            └── solr-9.x-sample/
```

Obsoletes:
- `kiro-cli/` directory on scaffolding branch (folded into this skill)
- `MigrationCompanion/skills/solr-opensearch-migration-advisor/`
  (subsumed; Solr expertise lives in steering + references)

Preserves:
- `MigrationCompanion/opensearch-pricing-calculator/` (vendored Go
  service, independent, stays as-is; skill references it)

---

## What exists vs. what's missing

| Piece                                   | Status                            |
|-----------------------------------------|-----------------------------------|
| `userSchemas.ts` accepts SOLR           | ✅                                 |
| `migrationAssistantWithArgo` helm chart | ✅                                 |
| `testClusters` with Solr Operator       | ✅                                 |
| `SolrReader` Java library               | ✅ substantial, not E2E tested     |
| `minikubeLocal.sh`                      | ✅                                 |
| kiro-cli steering (ES/OS)               | ✅ needs merging + generalizing    |
| Solr advisor SKILL.md                   | ✅ needs merging + expanding scope |
| `solr_schema_to_os_mapping.py` etc.     | ✅ on scaffolding branch           |
| Unified SKILL.md (5 phases)             | ❌ to write                        |
| `query_parity.py`                       | ❌ to write (~100 LOC)             |
| `ground_truth_collect.py`               | ❌ to write (~150 LOC)             |
| `plan_json_validate.py`                 | ❌ to write (~50 LOC)              |
| `support_bundle.py`                     | ❌ to write (~50 LOC)              |
| `migration-plan.schema.json`            | ❌ to write (extends userSchemas)  |
| `ground-truth.schema.json`              | ❌ to write                        |
| Fixtures (ES + Solr sample clusters)    | ❌ to write                        |

Heavy infrastructure is done. What's left is markdown, a handful of
Python scripts, two JSON schemas, and two fixture datasets.

---

## Iteration plan — how we build it

Now that this is a skill, not a binary, "build" means:
1. Scaffold the skill directory
2. Write SKILL.md + steering for one phase
3. Test by invoking the skill with kiro-cli (or Claude Code) in ACP mode
   against a fixture snapshot
4. Observe where the agent stumbles, fix the steering, re-test
5. Move to next phase

Recommended first iteration target: **Phase 1 offline-mode, ES→OS,
tiny fixture snapshot**. Proves:
- Agent can bring up minikube + MA helm chart deterministically
- Agent can submit + observe a workflow via `kubectl exec`
- Agent captures ground truth in structured form
- Agent runs query parity
- Agent writes a report

Everything else (live mode, Solr, Phase 3, Phase 4) is incremental
once that loop works.

---

## Risks and honest edges

- **Sandbox run time scales with source size.** Large customers hit a
  wall until MA native sampling exists. Three opt-outs documented above.
- **Corporate Docker fleets / Windows/WSL2 / M-series Macs** can be
  hostile to minikube. BYO and cloud-sandbox escape hatches exist.
- **SolrReader hasn't been exercised E2E.** First real end-to-end run
  happens inside `advise` sandbox runs. Feature, not bug — the skill
  becomes the forcing function that hardens SolrReader.
- **Query-parity threshold (70% top-10 overlap)** is a heuristic; needs
  tuning against real customer queries before GA.
- **Multiple IDE agents** — skill format has to work on Kiro, Claude
  Code, Cursor, Cline. SKILL.md frontmatter and tool-invocation patterns
  differ slightly between them. Tested on Kiro first; validated on
  Claude Code before calling it IDE-agnostic.

---

## Open decisions

- **Scaffolding branch disposition.** Recommend: extract the
  `migrationAssistantWithArgo` chart refinements and pricing-calculator
  as narrow PRs; rewrite advisor skill from scratch as unified
  migration-companion skill (don't rebase-merge the Solr-only version,
  fold its references and scripts into the new structure).
- **v1 IDE agent matrix.** Kiro-only for v1 with stated intent to
  support Claude Code by v1.1? Or ship IDE-agnostic v1?
- **Minikube vs Kind for the sandbox.** Decided: **kind.** More reliable
  across Docker-in-Docker setups; no VM layer.

---

## What this doc is NOT

- Not an implementation plan; no task list, no file-by-file diff.
- Phase names and file names are placeholders that feel right.
- Not a merge plan for `migration-companion-scaffolding`.

Next: once signed off, this becomes the input to a writing-plans-style
bite-sized plan to actually scaffold the skill.
