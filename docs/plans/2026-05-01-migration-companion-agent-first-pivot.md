# Migration Companion — Agent-First Pivot (v4 Design Plan)

Status: **DRAFT for iteration.** v5 iteration implemented under
`migrationCompanion/` (SKILL.md + steering/ + references/ + demo/).
Refs: #2503, #2444, #2504
Supersedes (design-level): `docs/plans/2026-04-30-migration-companion-unified-ux.md` where it conflicts.
Base: branch `companion-plan` @ `902be0d88`

## v5 addendum (2026-05-01)

This document's §6 layout has been superseded by the implemented layout
on disk. Sections 1–5 and §7–§10 are still the source of rationale.

v5 implemented changes vs. v4 proposal:

- **No `scripts/` directory.** The agent composes `curl | jq` and
  `kubectl exec` directly. No helper scripts — bash, Python, or
  otherwise.
- **No `templates/` directory.** The agent builds `config.yaml`
  against the live JSON Schema at
  `/root/.workflowUser.schema.json` inside the migration-console pod.
- **No `schemas/migration-plan.schema.json`.** The canonical schema is
  the one shipped with `orchestrationSpecs` and mounted into the pod;
  we don't maintain a second schema.
- **No `references/engine-fingerprints.md`, `ma-workflow-contract.md`,
  `known-incompatibilities.md`.** Those artifacts are either
  recomputed at probe time (fingerprints) or retrieved live
  (workflow contract via `workflow configure sample`). The one
  reference kept is `references/ma-workflow-cli.md` which points at
  `kiro-cli/kiro-cli-config/steering/workflow.md` plus the pod-exec
  shape.
- **Steering files are numbered phases** (00-schema-refresh,
  01-interview-probe, 02-scaffold-config, 03-secrets-submit,
  04-validate-parity-relevancy, 05-report, 99-pitfalls).
- **Layer 2/3 validation is explicit.** `steering/04-*` replaces the
  v4 "parity-rules" with three distinct layers: structural parity,
  query-shape battery (5–10 queries the agent picks), and relevancy
  showcase (2–3 natural-language queries with top-5 tables + narrative).
- **Demo drivers consolidated.** v4 `00-reset/01-setup/02-install/03-run`
  became `00-reset.sh` + `install-skill.sh` +
  `es-to-os.sh` + `solr-to-os.sh` (stub). Single command per migration
  path.
- **Runtime artifacts live in `migrationCompanion/runs/`** (gitignored),
  not `/tmp/companion-demo/`.

## 1. Why this plan exists

The v3 Design Plan (2026-04-30) committed on paper to a skill-based,
agent-driven companion. Iteration 0 field experience then led us to
build Python scaffolding — `probe_source.py`, `parity_check.py`,
`emit_workflow.py`, `validate_plan.py`, `run_empirical.py` (~1,100 LOC)
plus a schema enum and a steering prompt with 13 `python3 scripts/...`
shell-outs — to make the iter-0 demo reliable.

That scaffolding works for the one path it was written for (ES 7.10.2 →
OS 3.1.0). It does NOT generalize, because **every engine/version
quirk is encoded in Python, not in the agent's reasoning**. Concretely:

| Surface | Knowledge encoded | Maintenance footprint per new engine/version |
|---|---|---|
| `schemas/migration-plan.schema.json` | engine enum `[elasticsearch, opensearch, solr]`, target enum `[opensearch]` | schema edit + validator re-release |
| `scripts/probe_source.py` (274 lines) | `identify_engine()` string matching; `probe_es_os()` + `probe_solr()` per-engine branches; `HARD_MODE_MAPPING_TYPES` ES-specific; `SYSTEM_INDEX_RX` per-engine magic prefixes | new branch per engine; new entries per new-in-version type (e.g. `semantic_text`, `rank_vectors`) |
| `scripts/parity_check.py` (154 lines) | ES/OS-only today; hits `/_cat/indices`, `/_mapping`, `/_settings`, `/_count` | new branch per engine; Solr's `/solr/<c>/select?rows=0` → `numFound` would be a copy-paste |
| `scripts/emit_workflow.py` (242 lines) | `format_version_token` per-engine version-string munging | new token rules per engine × version family |
| `scripts/validate_plan.py` (112 lines) | Version-prefix registry per engine | new prefix per engine |
| `demo/02-install-steering.sh` | 13 `python3 scripts/...` shell-outs tell the agent to defer to our code | steering prompt edits per new script surface |

Net: **five files change per new source engine; more per new version
family; the agent is a conversational veneer over our code, instead of
the brain using our code as dumb hands.**

The user's explicit ask is the inverse: **lean on the agent to do the
heavy lifting**, keep the maintained surface small, support "Solr + all
past versions" without a treadmill. This plan commits to that pivot,
including breaking changes and deletions.

---

## 2. Guiding constraints

Invariants that must hold through iteration:

1. **Agent is the brain.** Per-engine, per-version knowledge lives in
   the agent's reasoning, grounded by a small set of curated references
   it reads on demand. Not in Python enums or regex tables.
2. **Scripts are dumb hands.** Anything we ship as code must be
   engine-agnostic and version-agnostic. If it has an `if engine ==
   "solr":` branch, it's the wrong abstraction.
3. **Stable contract = MA's boundary, nothing else.** The only
   rigid, schema-validated contract is what the Migration Assistant
   workflow itself consumes (Argo WorkflowTemplate inputs + MA CRDs).
   Upstream of that is agent territory.
4. **Empirical-first.** The report the user reads must be grounded in a
   real migration the agent just ran, not a predicted one. (v3 already
   committed to this; we keep it.)
5. **No new binaries.** Companion is a skill directory an ACP-capable
   agent loads. No `mc` CLI, no daemon, no server.
6. **Demo stays two-command.** `01-setup-cluster.sh` (or Solr variant) +
   `03-run-companion.sh`. The agent does everything between.
7. **No regressions to iter-0 ES 7→OS 3 path.** The working demo must
   keep working through the pivot; the pivot is additive to capability
   and subtractive to code.
8. **No proprietary-name references in commits/PRs/authored docs**
   (runtime product naming in the tool that literally migrates from it
   is fine).

---

## 3. End-to-end narrative — what changes, what stays

### 3.1 What the user sees (unchanged from v3)

```
$ bash migrationCompanion/demo/01-setup-cluster.sh
  (stands up kind + MA + source + target; writes cluster-versions.env)

$ bash migrationCompanion/demo/03-run-companion.sh
  -> agent loads migrationCompanion/ as a skill
  -> agent probes source and target directly with curl (see 3.2)
  -> agent interviews the user (free-form, not a fixed question count)
  -> agent emits migration-plan.json, validates against MA's contract
  -> agent submits the Argo workflow, watches it, handles ApprovalGates
  -> agent runs parity checks (see 3.3)
  -> agent writes report.md

Artifacts at /tmp/companion-demo/:
  plan.json  workflow.yaml  report.md  transcript.log
```

The user-visible surface does not change. What changes is **what's
behind the curtain**.

### 3.2 What the agent does instead of `probe_source.py`

Today: we shell to Python. Python does per-engine HTTP with hardcoded
endpoints, matches strings to detect engine, extracts fields from
hand-enumerated response shapes.

After pivot: steering says, in plain English,

> "Identify what's running at this endpoint. Try `GET /` first — if the
> response has `version.distribution = opensearch`, it's OpenSearch;
> any other `version.number` under a Lucene-style root is Elasticsearch
> (use the `number` to decide 1.x/2.x/5.x/6.x/7.x/8.x compatibility).
> If `GET /` returns HTML or a SolrCloud admin page, hit
> `/solr/admin/info/system` — `lucene.solr-spec-version` gives you the
> Solr version. If you see neither shape, call that out to the user and
> stop."

Plus a `references/engine-fingerprints.md` table with 6-8 curly-braced
example responses so the agent can pattern-match confidently.

The agent already knows the difference between ES 5.x `_all`,
ES 6.x single-type indices, ES 7.x typeless, ES 8.x `semantic_text`,
Solr 4 `schema.xml` vs Solr 9 managed-schema, etc. It does not need us
to enumerate those in `HARD_MODE_MAPPING_TYPES`.

**Net:** `probe_source.py` (274 lines) → deleted. Replaced by 1 page of
reference markdown and ~15 lines in SKILL.md.

### 3.3 What the agent does instead of `parity_check.py`

Today: Python hits ES-shaped URLs; would need a Solr branch.

After pivot: steering says,

> "For each index/collection in the plan, get a document count from the
> source and from the target. Source: `_count` for ES/OS;
> `/solr/<coll>/select?q=*:*&rows=0` for Solr (read `response.numFound`).
> Target: always `_count`. Compare. Same-numbers = PASS. Different = note
> the delta and hypothesize a cause from the workflow log (rejected
> docs, mapping conflicts, `_id` collision, dropped fields)."

`scripts/count_docs.sh <url> <index_or_collection>` — 15 lines of
bash/curl, no engine awareness, the agent picks the URL shape.

**Net:** `parity_check.py` (154 lines) → deleted, replaced by one
curl wrapper and ~20 lines of steering.

### 3.4 What the agent does instead of `emit_workflow.py`

Today: Python renders `workflow.yaml` with per-engine version-token
munging.

After pivot: one Jinja-or-plain-yaml template
`templates/workflow.yaml.j2` with named variables. Agent fills it.
Before apply, the agent validates the rendered YAML against MA's
published Argo WorkflowTemplate input schema (the *real* contract).

**Net:** `emit_workflow.py` (242 lines) → deleted. Replaced by one
template (~60 lines YAML) + steering that tells the agent what a
legal MA input looks like, sourced from MA's own schema.

### 3.5 What the agent does instead of `validate_plan.py`

Today: Python JSON-schema validation of `migration-plan.json`, plus a
per-engine version-prefix registry.

After pivot: **delete `migration-plan.json` as a separate artifact.**
The plan was a stepping stone between the interview and the workflow.
The agent can render straight to `workflow.yaml` (validated against
MA's schema) and a human-readable `report.md`. If the user wants to
edit something before apply, they edit `workflow.yaml` directly; that's
the real contract anyway.

**This also changes the report.** The "Reproduce This Migration" section
in `report.md` today emits `migration-plan.json` as "the authoritative
spec" and tells the user to run our Python helpers to reproduce. After
pivot, the reproduction section emits the exact `workflow.yaml` the
agent submitted to MA (credentials redacted), plus the literal
`argo submit` or `kubectl apply` command. No intermediate artifact, no
companion-repo dependency — a user with just their clusters, MA
installed, and this YAML can re-run the migration. The YAML is the
reproduction spec; MA's `full-migration-imported-clusters`
WorkflowTemplate is the contract.

**Net:** `validate_plan.py` (112 lines) → deleted. `migration-plan.schema.json`
(159 lines) → deleted. One less translation layer, one less schema to
keep in sync with engine versions.

### 3.6 What the agent does instead of `run_empirical.py`

Today: Python orchestrates the dry-run (submit workflow, poll,
approve gates, collect results).

After pivot: the agent already has `kubectl`, `argo`, and `curl`. It
polls directly, parses Argo's native output, patches ApprovalGates with
`kubectl patch`. We keep exactly **one small script**:
`scripts/apply_workflow.sh` (~30 lines) that does `kubectl apply` +
`argo watch` and nothing else.

**Net:** `run_empirical.py` (344 lines) → deleted. Replaced by ~30 lines
of bash and the steering procedure.

### 3.7 What stays

Things that are genuinely hard for an agent to derive on the fly, or
are real external contracts:

- **MA's Argo WorkflowTemplate input schema** — real contract, pulled
  from the TypeScript workflow templates repo at
  `orchestrationSpecs/packages/migration-workflow-templates/`. Cached
  into `references/ma-workflow-contract.md`.
- **Known pitfalls from iter-0 (PF1–PF9)** — hard-won facts an agent
  cannot reason to (e.g. work-item name character restrictions,
  RFC-1123 snapshot names, ApprovalGate timing). Stay in
  `steering/pitfalls.md`.
- **Demo bring-up scripts** (`01-setup-cluster.sh`, Solr variant) — these
  are deterministic infra, not agent territory.
- **Parity-count curl wrapper** (`scripts/count_docs.sh`) — ~15 lines.
- **Workflow apply wrapper** (`scripts/apply_workflow.sh`) — ~30 lines.
- **Workflow YAML template** (`templates/workflow.yaml.j2`) — ~60 lines.
- **Steering prompt files** (`steering/*.md`) — the new primary
  surface.

### 3.8 Size budget

| | Before | After (target) |
|---|---|---|
| Python | ~1,100 LOC | 0 |
| Shell (helpers) | — | ~50 LOC |
| YAML templates | — | ~60 LOC |
| JSON schemas we maintain | 1 (159 lines) | 0 |
| Steering markdown | ~0 | ~800 LOC across 5-7 files |
| References (curated facts) | ~0 | ~400 LOC across 3-4 files |

Net code reduction: ~1,100 Python lines deleted. Net documentation
growth: ~1,200 markdown lines the agent reads. That's the trade we
want — swapping "code that encodes engine knowledge" for "prose the
agent reasons from".

---

## 4. Proposed layout after pivot

```
migrationCompanion/
├── README.md
├── SKILL.md                              <- primary agent entrypoint (NEW)
├── steering/
│   ├── 01-interview.md                   <- free-form, not 6 fixed Qs
│   ├── 02-probe-and-fingerprint.md       <- how to identify source
│   ├── 03-plan-and-emit-workflow.md      <- render workflow.yaml
│   ├── 04-run-and-watch.md               <- apply, gates, polling
│   ├── 05-parity-and-report.md           <- count docs, write report
│   └── 99-pitfalls.md                    <- PF1-PF9 carried over
├── references/
│   ├── engine-fingerprints.md            <- example response shapes
│   ├── ma-workflow-contract.md           <- MA's Argo input schema
│   └── known-incompatibilities.md        <- curated ES/Solr → OS table
├── templates/
│   ├── workflow.yaml.j2                  <- MA workflow input
│   └── report.md.j2                      <- report skeleton
├── scripts/
│   ├── count_docs.sh                     <- engine-agnostic count wrapper
│   └── apply_workflow.sh                 <- kubectl apply + argo watch
├── demo/
│   ├── 00-reset.sh                       <- unchanged
│   ├── 01-setup-cluster.sh               <- unchanged (ES path)
│   ├── 01-setup-cluster-solr.sh          <- NEW (Solr path)
│   ├── 02-install-steering.sh            <- simplified; loads the skill
│   └── 03-run-companion.sh               <- unchanged shape
└── examples/
    └── es7-to-os3-plan.json              <- delete; plan.json goes away
```

Deletions relative to current tree:

- `scripts/probe_source.py`
- `scripts/parity_check.py`
- `scripts/emit_workflow.py`
- `scripts/validate_plan.py`
- `scripts/run_empirical.py`
- `schemas/migration-plan.schema.json`
- `examples/*-plan.json`

---

## 5. Seams / contracts

Named interfaces the design hangs on. These are the things that need to
stay stable through iteration; everything else is fair game.

### Seam A: Agent ↔ Source/Target cluster
- **Protocol:** HTTP, agent drives `curl`.
- **Contract:** whatever the engine natively speaks. Agent fingerprints
  it from `references/engine-fingerprints.md` and reasons from there.
- **Why a seam:** the ONLY place engine diversity enters the system.
  Keeping it in the agent's reasoning (not our code) is what makes
  "Solr + all past versions" free.

### Seam B: Agent ↔ Migration Assistant
- **Protocol:** Argo WorkflowTemplate submission + MA CRDs
  (ApprovalGate, Migration).
- **Contract:** MA's published input schema, pulled from
  `orchestrationSpecs/packages/migration-workflow-templates/`.
  Rendered into `references/ma-workflow-contract.md`.
- **Why a seam:** this is MA's real external API. It changes when MA
  changes. It deserves a rigid spec.

### Seam C: Agent ↔ User
- **Protocol:** natural language, plus the two demo bash scripts.
- **Contract:** none (that's the point).
- **Why a seam:** user-facing. Forcing structure here is what got us
  the 6-question interview that forced us to encode engine knowledge
  up front. Free-form wins.

### Seam D: Skill ↔ Agent runtime (ACP)
- **Protocol:** SKILL.md + steering/* + references/*.
- **Contract:** Hermes `skill_view` / Kiro `agent.json resources` /
  Claude Code skill loader conventions.
- **Why a seam:** defines how the skill is loaded by any ACP-compatible
  agent, not just Kiro.

---

## 6. What exists vs. what's missing

| Thing | Exists today? | After pivot |
|---|---|---|
| ES 7.10.2 → OS 3.1.0 demo end-to-end | Yes (iter-0) | Must keep working |
| Solr 9.x demo (techProducts) | No | Added via `01-setup-cluster-solr.sh` only — no Python branches |
| ES 5.x / 6.x / 8.x support | No (schema enum allows, code doesn't) | Free (agent handles from fingerprint) |
| Solr 4-8 support | No | Free (agent fingerprints from `/solr/admin/info/system`) |
| MA workflow contract doc (for agent) | Implicit, scattered | `references/ma-workflow-contract.md` |
| Engine fingerprint reference | No | `references/engine-fingerprints.md` |
| Known-incompatibilities table | Partial in iter-0 report | Curated, per-source-version, in `references/` |
| Python probe/parity/emit/validate/run scripts | Yes (~1,100 LOC) | Deleted |
| `migration-plan.json` intermediate artifact | Yes | Deleted; agent goes straight to `workflow.yaml` |
| Bash 3.2-compatible demo scripts | Yes (fixed in `902be0d88`) | Keep |
| Skill structure (`SKILL.md`, `steering/`) | README describes it; doesn't exist on disk | Create |

---

## 7. How Solr + "all past versions" falls out for free

The user's specific concern: does this design actually make
Solr-plus-all-past-versions low-maintenance?

**Solr 4–9:** agent hits `/solr/admin/info/system`, reads
`lucene.solr-spec-version`, and knows (from its training) what
schema.xml vs managed-schema means, what legacy `copyField`s look like,
what Solr 4's `<dynamicField>` semantics are, etc. Our contribution is
one page of reference examples and one 15-line curl wrapper. Cost per
new Solr minor version: **zero lines of code.**

**ES 1.x–8.x:** agent hits `GET /`, reads `version.number`, knows (from
its training) that ES 1.x has `_type`-per-doc-multi-type, ES 6 is
single-type transitional, ES 7+ is typeless, ES 8.x has
`semantic_text`/`rank_vectors`/`sparse_vector`. Cost per new ES
version: **zero lines of code,** plus a curated-incompatibilities entry
IF we discover a new surprise in the field (and only then).

**OS 1.x–3.x target:** already typeless; agent translates downward as
needed. Same story.

**The one cost we accept:** when the agent is wrong about a specific
engine×version quirk, we patch `references/known-incompatibilities.md`
or `steering/99-pitfalls.md`. Patching prose is cheaper than patching
Python + re-releasing, and it's additive — no breakage to existing paths.

**The one cost we do not eliminate:** MA's own contract evolves. When
it does, `references/ma-workflow-contract.md` and
`templates/workflow.yaml.j2` need a patch. That's a real external API
boundary; no design pivot removes it.

---

## 8. Breaking changes this pivot introduces

Enumerated so we don't surprise anyone:

1. **`migration-plan.json` goes away.** Anyone (human, test, doc) that
   references it must be updated. The agent now emits `workflow.yaml`
   directly. `examples/*-plan.json` deleted.
2. **`migrationCompanion/scripts/*.py` all deleted.** Anything calling
   them breaks. Grep-checked: only `demo/02-install-steering.sh` and
   `demo/03-run-companion.sh` reference them today; both get rewritten.
3. **`schemas/migration-plan.schema.json` deleted.** Nothing outside
   the directory depends on it (grep-confirmed).
4. **`02-install-steering.sh` rewritten** to register the skill
   directory instead of individual `file://` resources per script.
5. **Iter-0 ES 7 → OS 3 path runs through agent reasoning, not Python.**
   Same demo, same artifacts, same report headers — different path
   behind the curtain. Acceptance: same parity counts, same workflow
   YAML shape (modulo cosmetic diffs).

None of these leak outside `migrationCompanion/`. The rest of the repo
is untouched.

---

## 9. Open questions for the human

Numbered so you can answer by number.

1. **Scope of the pivot in this branch.** Three options:
   - **(a)** Full pivot — delete all five Python scripts, rewrite
     steering, rewrite demo/02, add Solr demo, single PR. Big change,
     clean endpoint. *My recommendation.*
   - **(b)** Incremental — keep Python scripts as a fallback for one
     release while the agent-driven path matures; delete in a follow-up.
     Safer, two PRs, temporary duplication.
   - **(c)** Agent-driven for Solr only — leave ES path on Python, do
     Solr the new way as a proof point. Cheapest, but cements the
     treadmill we're trying to escape.

2. **Where does MA's workflow contract get pulled from?**
   - **(a)** Build-time extraction from
     `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/fullMigration.ts`
     during demo setup, cached into `references/ma-workflow-contract.md`.
     Agent always reads the fresh one. *My recommendation.*
   - **(b)** Hand-maintained `references/ma-workflow-contract.md`,
     updated manually when MA changes. Simpler, higher drift risk.

3. **Do we keep the `migration-plan.json` artifact at all?** My plan
   deletes it. Alternative: keep as a read-only debug artifact (agent
   writes it for transparency, nothing reads it). No strong opinion;
   deletion is cleaner, keeping costs ~20 lines of steering. *Leaning
   delete.*

4. **Solr demo scope.**
   - **(a)** Just techProducts, one collection, doc-count parity only.
     *My recommendation — matches "v1 best-effort" posture.*
   - **(b)** techProducts + films + books (all three stock sample
     collections), doc-count + field-name survey.
   - **(c)** techProducts + a custom `schema.xml` that exercises
     copyField, dynamicField, and legacy types — stress-tests the
     agent's reasoning. Good demo, more setup work.

5. **How strict should workflow.yaml validation be before apply?**
   - **(a)** Agent renders → `kubectl apply --dry-run=server`
     (MA's real admission webhook validates). *My recommendation —
     trust the real contract, not a shadow schema.*
   - **(b)** Agent renders → local JSON-schema check against cached
     contract → then `kubectl apply`. Belt+suspenders, more code.

6. **Steering file count.** I've proposed 5 numbered steering files +
   1 pitfalls file. Alternative: one big `STEERING.md` with sections.
   Multiple files make `skill_view(file_path=...)` loads cheaper and
   let the agent pull only the section it needs. *Leaning multi-file.*

7. **Do we delete or preserve `docs/plans/2026-04-30-...md` (v3 design)?**
   I'd keep it as history; this v4 supersedes it only where they
   conflict. Plans-as-history has value. *Leaning keep.*

8. **Git hygiene for the pivot.** If you pick (1a), that's a big
   single commit. Alternative: stack of commits — (i) add skill
   structure, (ii) add new demo path in parallel, (iii) cut over
   demo/02 + demo/03 to the skill, (iv) delete Python scripts + schema
   last. Reviewable as a stack. *Leaning stack of 4-5 commits on
   `companion-plan` branch.*

---

## 10. Iteration rhythm

After you answer the open questions (or tell me which to re-pitch):

1. I update this file in place to `Status: APPROVED, revision N`.
2. I write a separate **Implementation Plan** using the
   `writing-plans` skill — bite-sized tasks, exact file paths,
   TDD where applicable, per-task commit boundaries — saved to
   `docs/plans/2026-05-XX-migration-companion-agent-first-impl.md`.
3. Only after that plan is also signed off do I touch code.

**Nothing has been implemented for this plan. No files under
`migrationCompanion/scripts/`, `schemas/`, or `demo/` have been
modified as part of this document.** The last code change on this
branch remains the bash 3.2 fix at `902be0d88`.

---

*Authored by Andre Kurait with Hermes.*
