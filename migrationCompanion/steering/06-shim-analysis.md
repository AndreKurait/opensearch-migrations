# Phase 5b — Translation-shim analysis (Solr sources only)

Solr is not ES-wire-compatible with OpenSearch. The migration path therefore
has *two* surfaces that must land parity, not one:

1. **Data-plane parity** — documents and mappings in the target cluster
   match the source (Phases 4–5).
2. **Query-plane parity** — a Solr client, pointed at the Translation
   Shim, gets responses whose shape and rank match what Solr itself
   would have returned.

This steering file is about surface 2. **Skip it for ES / OS sources.**

## When this phase runs

- The source you probed in Phase 1 advertised Solr (either SolrCloud or
  standalone — `curl …/solr/admin/info/system` returned a `mode` field).
- A Translation Shim is reachable (the demo wires one up on
  `http://localhost:18080`; a real migration would have one fronting the
  target OpenSearch).

If both are true, run this phase after Phase 5 and before writing the
report.

## What the shim actually is

A single Java process that:

- listens on an HTTP port as if it were Solr
- forwards each incoming request through a JS transformer
  (`solr-to-opensearch-request.js`) to OpenSearch
- forwards the OpenSearch response back through
  `solr-to-opensearch-response.js` to look like a Solr response
- in **dual-target mode**, optionally also sends the original request
  verbatim to Solr and **cross-validates** the two responses against each
  other (field-equality + doc-count comparators), emitting per-request
  reports to a FileSystem sink and an `X-Validation` response header

You don't need to care how the JS transformer is implemented. You care
about **what it captures**, because those captures are the query-plane
parity evidence for the report.

## How to drive it

The demo sandbox (`solrMigrationDevSandbox/`) exposes:

| Endpoint | Role | Purpose |
|----------|------|---------|
| `http://localhost:18983` | Solr direct | Ground truth |
| `http://localhost:19200` | OpenSearch direct | Target data-plane |
| `http://localhost:18080` | Shim (single-target, OS only) | "Does my Solr query even translate?" |
| `http://localhost:18084` | Shim (dual, OS primary) | Cross-check OS-as-primary vs Solr witness |
| `http://localhost:18083` | Shim (dual, Solr primary) | Cross-check Solr-as-primary vs OS witness |

Fire the in-tree batch:

```bash
cd solrMigrationDevSandbox
python3 -m src.run_queries \
  --solr-url http://localhost:18983 \
  --shim-url http://localhost:18080 \
  --dual-url http://localhost:18084 \
  --queries queries/queries.json
```

Then extract the shim's per-request reports from the named docker volume
into the run dir:

```bash
docker run --rm -v shim-reports-os:/src -v "$(pwd)/runs/<ts>:/dst" \
  alpine sh -c 'cp -r /src/. /dst/shim-reports-os/'
```

Do the same for `shim-reports-solr` if both dual modes were exercised.

## What to record in `runs/<ts>/`

```
runs/<ts>/
├── shim-summary.json              # your aggregation, see below
├── shim-reports-os/               # raw FileSystem-sink dumps (OS primary)
│   ├── <request-id>.request.json
│   ├── <request-id>.response.json
│   └── <request-id>.validation.json
├── shim-reports-solr/             # raw FileSystem-sink dumps (Solr primary)
└── shim-queries/
    ├── <q-id>.solr.json           # direct-Solr response
    ├── <q-id>.shim.json           # single-target shim response
    └── <q-id>.dual.json           # dual-target shim response (primary copy)
```

Build `shim-summary.json` yourself from the raw sink output. Minimum
fields:

```json
{
  "total_queries": 167,
  "categories": 54,
  "single_target": {
    "succeeded": 167,
    "failed": 0,
    "avg_latency_ms_solr": 14,
    "avg_latency_ms_shim": 31
  },
  "dual_target_os_primary": {
    "validated": 160,
    "validation_failed": 7,
    "categories_with_drift": ["grouping", "spellcheck"]
  },
  "validation_failures": [
    {"query_id": "q_grouping_1", "category": "grouping",
     "field_path": "grouped.brand.groups[0].doclist.numFound",
     "solr_value": 17, "opensearch_value": 16}
  ]
}
```

The `validation_failures` list is the load-bearing artifact. Every entry
is a concrete, named drift a user can triage.

## Classification, not celebration

Drift from the shim falls into four buckets. Classify each failure; do
not bucket-less-count.

1. **Protocol round-trip** — the shim translated the request fine but
   OS cannot express the feature (e.g. Solr `<updateRequestProcessor>`
   chains, grouping with certain collapse flags, spellcheck dictionaries
   built from Solr-specific analyzers). These are *expected* drift and
   should be listed by name in the report's open items.
2. **Noise** — QTime, `responseHeader.params.NOW`, shard-state
   timestamps, per-replica ordering of equal-score hits. The shim's
   default validator config ignores these; if you see them in
   `validation_failures`, it means the ignore list needs extension, not
   that the migration is broken.
3. **Real rank drift** — top-N IDs or scores moved. This is the
   interesting signal. Report it with the same rank-metric discipline
   as Phase 5 (Jaccard@10, Spearman-ρ, Kendall-τ, nDCG@10). Undefined
   metrics (single-hit, size:0) must still be reported as "undefined",
   not 0.
4. **Transform bug** — the shim 500'd, returned malformed JSON, or the
   response failed to deserialize. These are shim bugs, not migration
   issues. Report them as blockers distinct from user-visible drift.

**Do not mix the four.** The reader needs to know "how many of these
rows are things I can fix" vs "how many are fundamental to the product
jump".

## Pitfalls specific to the shim

- The FileSystem sink writes one request file per HTTP request, not per
  logical query. A cursor-paginated query with 20 pages produces 20
  request files. Your aggregation must group by `query_id` from the
  request body (or by `X-Test-Query-Id` header if the test harness
  injects one).
- `X-Validation` response header is truncated by default Python's 64KB
  header limit — the sandbox runner bumps `http.client._MAXLINE`. If
  you see `LineTooLong` in logs, that's the cause, not the shim.
- The dual-primary-Solr mode returns the Solr response to the client
  and validates OS against it; primary-OS does the inverse. Rank drift
  that only shows up under one primary (not both) is usually a
  response-transform bug, not a mapping issue — flag it accordingly.
- `shim-reports-solr` and `shim-reports-os` are **docker named
  volumes**, not bind mounts. You cannot `ls` them from the host
  without `docker run --rm -v <volume>:/src alpine ls /src`.
- The default FileSystem sink includes request + response bodies. For
  167 queries with cursor walks, expect ~40–80 MB under each volume.
  That is fine to commit under `runs/<ts>/` only if it's gitignored
  (which it is — the whole `runs/` tree is). Never commit shim-report
  bodies.

## Checkpoint

Before writing the report, confirm:

- [ ] `shim-summary.json` exists and every field is populated from real
  counts — not hand-written.
- [ ] Each `validation_failures[]` entry has a classification
  (round-trip / noise / rank-drift / transform-bug).
- [ ] At least one rank-drift sample from §6 of the report cites a
  specific file under `shim-reports-os/` or `shim-queries/`.
- [ ] If zero failures, the report says "0 validation failures across
  N queries" with `relation:"eq"`-style honesty — not "perfect parity".

If any item is false, you haven't finished this phase.
