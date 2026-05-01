# Phase 4/5 — Structural parity + query-shape + relevancy showcase

**This is the most important phase.** It's what turns "the workflow said
Succeeded" into "the migration actually worked." You design the tests,
not a fixed recipe.

There are three layers, in this order:

1. **Structural parity** — mechanical, must be green
2. **Query-shape battery** — 5–10 representative queries, diff results
3. **Relevancy showcase** — 2–3 natural-language queries with narrative

All queries run on **both** source and target, and everything gets
captured to `runs/<ts>/queries/<id>.{source,target}.json` so the report
is reproducible offline.

---

## Layer 1 — Structural parity (mechanical)

For each migrated index/collection:

```
# ES/OS source & target:
GET /<idx>/_count
GET /<idx>/_mapping

# Solr source (targets are always ES-API):
GET /solr/<coll>/select?q=*:*&rows=0
GET /solr/<coll>/schema/fields
```

Compare:

- **Doc count exact match is required.** Any delta is a bug — report it,
  investigate via the workflow logs before calling the migration done.
- **Field inventory**: set-diff field names. Ignore `_id`, `_source`,
  `_routing`, `_field_names`, `_seq_no`, `_primary_term`, `_version`,
  `_tier`, ingest-pipeline fields. Flag the rest.
- **Field type sanity**: `text` should stay `text`, `keyword` should
  stay `keyword`. Solr `string` → OS `keyword` is expected.
- **Analyzer sanity** on 1–2 text fields — for each:
  ```
  GET /<idx>/_analyze?field=<f>&text=<sample-from-real-doc>
  ```
  Token streams should match. If the target now uses `english` analyzer
  where source used `standard`, tokens will differ — note it, don't
  panic.

If Layer 1 fails (doc count mismatch, large field loss), **stop and
report before doing Layers 2 and 3.** No point running relevancy tests
on a broken migration.

### The empty-target case (target mapping present, docs = 0)

If `target./<idx>/_count` returns 0 on every migrated index but the
mappings are present and look correct, treat this as **partial
success**:

- **Metadata phase worked** — still diff source schema vs target
  mapping, still probe 1–2 analyzer behaviors against the live mapping,
  still list per-field type mappings in the report. None of this
  requires documents to exist on the target.
- **Backfill phase failed** — mark it so in the report. Skip Layer 2
  (query-shape battery) and Layer 3 (relevancy showcase) entirely;
  running them against an empty index produces no signal.
- **Do not** claim the workflow's `Succeeded` status means the
  migration succeeded. `workflow status` reflects what each step
  returned; it does not independently verify that documents landed on
  the target. Trust `target./<idx>/_count`, not the workflow phase.
- In the report's Summary section, lead with the partial status
  (metadata ✓, backfill ✗). In Section 8 (Incompatibilities flagged),
  describe what you observed directly — symptom (`_count=0`,
  `shard_complete=N/N`, workflow `Succeeded`), what you ruled out
  (mappings present, schema valid, auth succeeded), and what you
  could not rule out without more data (RFS worker logs, which Argo
  does not archive; snapshot reader compatibility with the source
  backup layout). Point the user at the relevant Solr-source references
  (`solrMigrationDevSandbox/README.md`,
  `AIAdvisor/skills/solr-opensearch-migration-advisor/`) as
  investigation starting points, not as conclusions.

---

## Layer 2 — Query-shape battery

You design the battery based on the corpus. Principles:

### Principle 1 — diversity beats volume

5–10 queries is enough. Cover the field types you actually see. A
sensible mix:

- **Exact term** on a `keyword` field — highest precision test
- **Full-text match** on a `text` field using a token you **observed in
  a real document during probe**
- **Phrase match** if there's a text field long enough to have phrases
- **Numeric range** if there's a numeric field
- **Date range** if there's a date field
- **Bool combinator** (must + filter + should)
- **Aggregation** on a `keyword` field (terms agg)
- **Sort + pagination** on a non-score sort key

Skip query shapes the corpus can't support. Don't fabricate tokens —
relevance tests with made-up text are worthless.

### Principle 2 — capture the same thing on both sides

For each query, record:

```
{
  "query_id": "q3_phrase_match_title",
  "source_hits_total": 1247,
  "target_hits_total": 1247,
  "source_top10_ids":   ["A", "B", ...],
  "target_top10_ids":   ["A", "B", ...],
  "source_top10_scores": [9.12, 8.47, ...],
  "target_top10_scores": [9.08, 8.51, ...],
  "source_took_ms": 14,
  "target_took_ms": 18
}
```

Write this to `runs/<ts>/queries/<id>.summary.json`.

### Principle 3 — judgment, not thresholds

You decide what's significant. Rough intuitions:

- Doc count or hit count off by 1–2 on a million-hit query = noise.
  Off by 30% on a 100-hit query = signal.
- Top-10 IDs with identical sets but swapped positions 7↔8 = noise.
  Top-3 entirely different = signal.
- Scores drifting 2–3% is expected (BM25 parameter differences across
  versions). Scores drifting 50%+ warrants looking at analyzer config.
- `took_ms` is not a correctness signal — note perf deltas separately.

Write the verdict in the report as a sentence, not a number. "Top-10
overlap Jaccard 0.9 — same documents, minor reorder, consistent with
analyzer parity." Include the raw numbers so the reader can overrule
your call.

### Principle 4 — translate Solr queries faithfully

When the source is Solr, you must run native Solr queries on the source
and their OpenSearch DSL equivalents on the target. Simple mapping:

| Solr                      | OpenSearch DSL                              |
|---------------------------|---------------------------------------------|
| `q=title:laptop`          | `{"match":{"title":"laptop"}}`              |
| `fq=cat:electronics`      | `{"term":{"cat":"electronics"}}` in filter  |
| `facet.field=manu`        | `{"aggs":{"by_manu":{"terms":{"field":"manu"}}}}` |
| `sort=price asc`          | `{"sort":[{"price":"asc"}]}`                |
| `q=*:*`                   | `{"match_all":{}}`                          |

Worked example (the kind of complete translation the agent should be
comfortable producing):

```
# Source (Solr techproducts):
curl 'http://solr:8983/solr/techproducts/select?\
q=title:ipod&fq=cat:electronics&facet=true&facet.field=manu&rows=10'

# Target (OpenSearch, post-migration):
curl -XGET https://os:9200/techproducts/_search -H 'Content-Type: application/json' -d '{
  "query": {
    "bool": {
      "must":   [{"match": {"title": "ipod"}}],
      "filter": [{"term":  {"cat":   "electronics"}}]
    }
  },
  "aggs": {
    "by_manu": {"terms": {"field": "manu", "size": 10}}
  },
  "size": 10
}'
```

For edismax, grouping, spatial, or other non-trivial Solr features,
translate as close as you can and flag the approximation in the report.

---

## Layer 3 — Relevancy showcase

Pick 2–3 **natural-language queries from content you saw during probe**.
If `/products/_search?q=laptop` returned hits in Phase 1, use "laptop" —
not a synthetic term.

Run each on both clusters with `size: 5`. Write a side-by-side top-5
table in the report with:

- rank
- doc ID
- one readable field (`title`, `name`, or whatever the corpus has)
- score
- a ✓ / Δ marker if the doc appears on both vs. only one side

Then narrate any drift with a **hypothesis**, not a verdict:

> "Doc `B07X9ABC` ranks #2 on source but doesn't appear in target
> top-5. The `title` field's mapping is unchanged, but the target's
> default analyzer was upgraded from `standard` (source ES 7.10) to
> OpenSearch 2.11's `standard` — stop-word handling may differ. Verify
> with `GET /<idx>/_analyze?field=title&text=<query>` on both clusters."

If no meaningful drift, say so plainly: "3/3 showcase queries returned
identical top-5 sets with scores within 3%. No narrative needed."

---

## Checkpoint 5

Summarize to the user before writing the report:

```
Structural parity:   23/23 indices OK
Query-shape battery: 8/10 within noise, 2/10 flagged
  - q5_numeric_range: hit-count delta 12% (investigating)
  - q7_agg:          2 new bucket values on target (expected, see notes)
Relevancy showcase:  3/3 showcase queries — top-5 overlap [5,5,4] of 5

Write report and stop? (yes / investigate / rerun with different queries)
```

Wait for confirmation before Phase 6.
