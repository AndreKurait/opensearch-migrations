# Phase 6 — Report

Goal: produce `runs/<ts>/report.md` that is both a human-readable summary
*and* a complete, empirically-traceable reproducer. Every numeric claim
must resolve to an artifact file in the same run directory.

## Size discipline

Target **~1–3 pages of rendered markdown**, roughly 8–12 KB. That length
is enough to carry a verdict banner, a reproduction block, a parity
table, 3–5 highlight rows, and a customer-impact callout.

If you find yourself at 20 KB with nine sections, you have drifted into
audit territory. Two legitimate moves:

1. Keep the audit version as `report.md` (committable artifact), and
   paste a trimmed ~8 KB subset into the PR body / user message.
2. Cut §7 narrative, inline §5.2 controls into `<details>`, merge §4
   probe snapshots into the parity table.

Prefer concision over completeness *in the report* — the raw JSON in
`runs/<ts>/` is where completeness lives.

## Sections, in order

Reports with more than ~5 visible sections get a jump-nav of anchor
links under the verdict banner so the reader can skip.

### §0 — Verdict banner (REQUIRED, first)

One short paragraph, before any heading. Must contain:

- the word **PASSED**, **PASSED with open items**, or **FAILED**
- honest counts of what was measured — if some cells are mathematically
  undefined (e.g. Spearman-ρ on a single-hit result set), report them
  as "N of N *defined* metrics … M cells undefined for trivial
  reasons". Never pretend every cell holds a number.
- one customer-impact sentence — what does this mean for a real
  user? ("Storage footprint is 2.20× to 2.43× smaller on target." —
  note: range edges must be the actual min/max from the data; do NOT
  round a 2.20–2.43 range out to "2.0–2.5" or in to "~2.3".)
- if there are open items, name them in one sentence.

Example (good):

> **Verdict: PASSED with one open item.** 9 query cases: 29 of 29
> defined rank metrics (Jaccard@10, Spearman-ρ, Kendall-τ, nDCG@10)
> are 1.0; 7 cells undefined (single-hit or `size:0` sets). Doc
> counts match 1:1 on all three user-data indices. Storage footprint
> is 2.20× to 2.43× smaller on target. Open item: `/_analyze` token
> parity not directly captured (clusters torn down before probe).

Example (bad — do not write):

> ✅ All 36 data points equal 1.0! Migration is perfect! 🎉

The bad version over-claims (some cells are undefined), uses
celebration instead of measurement, and buries the open item.

### §1 — Reproduction

Fenced block with the exact commands to replay this migration:

```bash
# Schema fingerprint: sha256:<12-char-prefix>
# (if the current schema differs, re-run may produce a different config)

cat <<'YAML' | kubectl exec -i -n ma migration-console-0 -- workflow configure edit --stdin
<paste runs/<ts>/config.yaml here>
YAML
kubectl exec -n ma migration-console-0 -- workflow submit
```

Include any `kubectl create secret generic …` commands the run needed
(**without** actual credentials — show `--from-literal=username=[REDACTED]`).

### §2 — Run metadata (honest)

A short table. Include:

- run timestamp (UTC ISO)
- source engine + version (from probe)
- target engine + version (from probe)
- schema sha256 (full hex)
- phase timings if measured (end-to-end, backfill, coord-bringup)
- chart version / git SHA of the deployed migration-console **if
  known**; otherwise explicitly write "not captured". Do not leave the
  reader guessing what was omitted vs. forgotten.

### §3 — Schema fingerprint

```
sha256: <full-hex>     file: /root/schema/workflowMigration.schema.json
```

Note if it matches a prior run in this repo's `runs/` directory.

### §4 — Probe snapshots

Source before / target before / target after.

For ES/OS: engine, version, index count, doc count, representative
field types, cluster health (note: a "yellow" source is expected
without replica nodes — don't flag it as a migration issue unless
shard states changed).

For Solr source: version, mode, collection list, per-collection field
count.

Keep this tight — the raw JSON lives in the same `runs/` dir.

### §5 — Structural parity

Markdown table, one row per index. Columns:

```
| Index | Source docs | Target docs | Mapping diff | Storage delta | Verdict |
```

For mapping diffs, explicitly state if the `properties` blocks are
byte-identical source↔target (strongest claim available). Any
non-trivial mapping diff goes in a `<details>` block beneath.

Storage delta deserves its own column — "target 35.6 KB vs source
87.2 KB = 2.45× smaller" is the kind of concrete number a reader
remembers.

### §6 — Query-shape battery

Classify each query before running. Two categories:

- **Load-bearing** — non-trivial queries whose cross-cluster parity
  is meaningful evidence (multi-hit, compound-bool, aggregation).
- **Controls** — trivial queries that "pass either way" (single-hit
  term match, `match_all` on empty index, size:0). These don't
  constitute evidence. Label them so the reader doesn't count them
  as load-bearing wins.

For each query, one `<details>` block:

```
<details>
<summary>q3_phrase_match_title — load-bearing — within noise</summary>

Query (source / target):
```json
…
```

| metric | source | target | delta |
| ... |

Verdict: <one sentence, cite the numbers>.

Undefined rank metrics (e.g. Spearman on a single-hit set) must be
reported as "undefined (single-hit)" — not 0, not "N/A", not omitted.
</details>
```

### §S — Translation-shim analysis (Solr sources only)

Include this section when the source is Solr. Omit it entirely for
ES/OS sources — do not leave an empty "N/A" stub.

Required sub-sections, in order:

1. **Classified failure counts** — one row per bucket (round-trip /
   noise / rank-drift / transform-bug). Zero counts are still rows;
   the reader needs to see that all four buckets were considered.
2. **Per-category drift table** — one row per query category from
   `queries.json` (boolean_query, dismax, grouping, spellcheck, …)
   with: total, succeeded, validation_failed, classification mix.
3. **2–3 concrete validation_failures** — each one cited by file
   under `runs/<ts>/shim-reports-os/` or `shim-queries/`. Paste the
   drifted field path, Solr value, OpenSearch value. No narration
   before the numbers.
4. **One-sentence verdict per primary mode** — "OS-primary: 160/167
   validated, 7 drift (4 round-trip, 3 noise). Solr-primary: not
   run in this session." Honest about what wasn't run.

Length budget: 8–15 lines of markdown + one `<details>` per cited
failure. Do not let this section out-grow §5/§6.

See `steering/06-shim-analysis.md` for the full shim-phase
discipline. The claim-trace rule (§2) applies here unchanged: every
number resolves to a file.

### §7 — Relevancy showcase (optional)

Include only if relevancy drift is the interesting story. If top-10
IDs were identical on all load-bearing cases, say so in one sentence
and skip the section entirely.

When included, one table per showcase query:

```
#### Query: "laptop"

| Rank | Source ID | Source title | Source score | Target ID | Target title | Target score | Match |
|------|-----------|--------------|--------------|-----------|--------------|--------------|-------|
| 1    | …         | …            | 9.12         | …         | …            | 9.08         | ✓     |

Commentary: <one paragraph, hypothesis not verdict>.
```

### §8 — Open items & next steps

Things the probe surfaced that didn't block the migration but the
user should know — each as a bullet with a concrete next step.
Examples:

- `/_analyze` token parity not directly captured → rerun with probe
  step 4b included before teardown
- custom Solr `<updateRequestProcessor>` chains with no OS
  equivalent → document replacement before production cutover
- score drift 2.4× on single-clause queries (compound queries
  unaffected) → expected from BM25 param differences; note in
  downstream tuning doc

Do NOT accumulate a "known issues" list that defers to lookups.
Describe the symptom in your own words with a concrete next step.

### §9 — Run artifacts (inventory)

```
runs/<ts>/
├── config.yaml
├── schema.json
├── schema.sha256
├── sample-from-pod.yaml
├── probe-source.json
├── probe-target-before.json
├── probe-target-after.json
├── queries/
│   └── q*.{source,target,summary}.json
├── rank-metrics.json
├── phase-timings.json
└── report.md              (this file)
```

## Claim-trace requirement (MANDATORY)

**Before you finish writing, every number in the report must resolve
to a specific artifact file in the run dir.** This is not optional —
without this step, the report is a story, not a measurement.

Minimum trace table (include as the final line of the report, or
equivalent in §2):

> Every numeric claim in this report traces to a file in
> `runs/<ts>/`: §2 timings ← `phase-timings.json`; §4 counts ←
> `probe-*.json`; §5 mappings ← `probe-source.*.mapping.json` (byte
> compared with `cmp`); §6 rank metrics ← `rank-metrics.json`; §6
> hit totals ← `queries/*.{source,target}.json` (`hits.total.value`,
> `relation:"eq"`).

See `references/report-claim-trace.md` for the verification
checklist — run through it before declaring the report finished.

## Pre-write review loop

Before writing the report, do five quick passes. Each one is a single
re-read with a specific lens:

1. **Structure** — sections in the right order, verdict first,
   jump-nav present if >5 sections, length in target range.
2. **Rigor** — every count is honest (defined vs undefined
   explicit), every range's edges are the actual extrema, no
   celebration-before-measurement, `relation:"eq"` checked on every
   hit total.
3. **Value** — does the reader come away with the one thing that
   matters? Run metadata honest about what wasn't captured.
4. **Consistency** — numbers referenced in §0 banner match §5/§6
   tables; no stale fuzzy numbers; arithmetic in headers (e.g.
   "35.6+11.9+7.5=55.0") actually adds up.
5. **Claim-trace** — every number resolves to a file. Open each
   cited file and confirm the number really lives there.

If a pass surfaces any fix, patch it before the next pass.

## Rules

- **Never include credentials.** Secrets appear as `[REDACTED]`
  everywhere. Check the final file before writing.
- **Never claim success that parity didn't demonstrate.** If doc
  counts don't match, the banner says "FAILED" and the summary says
  "Migration completed but structural parity failed; see §5."
- **Never round range edges inward.** `2.20× to 2.43× smaller` — not
  "~2.3×", not "2.0–2.5×".
- **Undefined is a real value.** Spearman-ρ on a single-hit set is
  mathematically undefined. Report it as such, not as 0 or omitted.
- **Keep the report committable.** Target 1–3 pages; push long query
  bodies into `<details>`; the run dir has the raw data.
