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

Verify `hits.total.relation` equals `"eq"` on every hit-total claim.
A `"gte"` relation means the number is a lower bound — never treat
it as exact.

Write the verdict in the report as a sentence, not a number. "Top-10
overlap Jaccard 0.9 — same documents, minor reorder, consistent with
analyzer parity." Include the raw numbers so the reader can overrule
your call.

### Principle 3a — undefined metrics are a real value

When computing rank-correlation metrics (Spearman-ρ, Kendall-τ) across
source↔target top-N lists, some cells will be **mathematically
undefined**:

- **Single-hit result set** — one element can't form a ranking; both
  ρ and τ are undefined (division by zero in the variance term).
- **Zero-variance ranking** — all elements tied at the same rank;
  same issue.
- **`size:0` query** (e.g. pure aggregation or count) — there is no
  hit list to correlate.

Report these as `undefined (single-hit)` or `undefined (size:0)` —
not 0, not 1, not "N/A", not omitted. When you summarize in the
verdict banner, count them separately: "N of N **defined** metrics
are 1.0; M cells undefined for trivial reasons."

Jaccard@K and nDCG@K *are* defined on single-hit sets (trivially 1.0
if both sides return the same single doc), so they don't need this
special-case handling.

### Principle 3b — classify each query before running

Label every query in the battery as either:

- **Load-bearing** — non-trivial queries whose cross-cluster parity
  is meaningful evidence. Multi-hit match queries, compound bool
  clauses, aggregations, numeric/date ranges.
- **Control** — trivial queries that pass either way. Single-hit
  exact-term match, `match_all` on an empty index, `size:0` probes.

Controls are fine to keep in the battery — they exercise code paths
and catch regressions — but they are NOT load-bearing evidence.
When the report summarizes "9 query cases passed", it must separately
note how many were load-bearing versus controls.

### Principle 3c — score drift is often bimodal

When scores drift between source and target, it's common for the
ratio to cluster into two modes:

- single-clause queries (`match`, `term`, `match_phrase` alone) drift
  one way (e.g. target/source ≈ 0.41–0.45)
- compound-bool queries with filters/multiple musts drift the other
  way (e.g. target/source ≈ 0.98)

Report the bimodality explicitly with the actual min and max — do
NOT collapse to a single average. "Scores drift bimodally:
0.4119–0.4545 on single-clause queries, 0.9808 on the one
compound-bool query."

Range edges in the report must be the actual extrema from the data.
Never round a 0.4119–0.4545 range out to "~0.4" or in to "0.42–0.45".

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
Structural parity:   23/23 indices OK, mappings byte-identical src↔target
Query-shape battery: 10 queries — 7 load-bearing, 3 controls
  Load-bearing: 6/7 within noise, 1 flagged
    - q5_numeric_range: hit-count delta 12% (investigating)
  Controls:     3/3 passed (trivially)
Rank metrics:        26 of 26 defined cells at 1.0; 4 cells undefined
                     (single-hit / size:0)
Score drift:         bimodal — 0.41–0.45× single-clause, 0.98× compound
Relevancy showcase:  3/3 showcase queries — top-5 overlap [5,5,4] of 5

Write report and stop? (yes / investigate / rerun with different queries)
```

Wait for confirmation before Phase 6.
