# Phase 1 — Interview & probe

Goal: know the source, the target, and the user's intent well enough to
build a config and design relevancy tests.

## Interview

Parse the user's initial prompt first. Only ask follow-ups for what is
actually missing. Minimum knowns you need:

1. **Source endpoint** (URL, protocol, port)
2. **Target endpoint** (URL, protocol, port)
3. **Auth for each** (basic via k8s secret name / sigv4 / mtls / none)
4. **Scope** — all indices/collections, an include list, or a regex
5. **Run mode** — snapshot-only (most common) or snapshot + live replay
6. **Approvals** — `skipApprovals: true` for demos, explicit gates for
   production

If the user is vague ("migrate my ES cluster"), infer what you can from
the probe and confirm at Checkpoint 1.

## Probe — Elasticsearch / OpenSearch

Use plain `curl` from the host against the endpoint the user gave you.
Capture each response to `runs/<ts>/probe-source.json` (and
`probe-target-before.json`).

Minimum probe set:

```
GET /
GET /_cluster/health
GET /_cat/indices?format=json
# For 3-5 representative indices (biggest, most varied):
GET /<idx>
GET /<idx>/_count
GET /<idx>/_mapping
GET /<idx>/_search?size=3          # sample documents
GET /<idx>/_analyze?field=<text-field>&text=<sample-from-a-real-doc>
```

Record the `version.number` from `GET /` verbatim and convert to the
schema form:

- ES 7.10.2 → `"ES 7.10.2"`
- OS 2.11.0 → `"OS 2.11.0"`
- OS 3.0.0  → `"OS 3.0.0"`
- Solr 9.7.0 → `"SOLR 9.7.0"` (see the Solr probe section for where
  the version string comes from — this is a different endpoint)

If the cluster is AWS-managed (endpoint matches
`*.es.amazonaws.com` or `*.aos.*.on.aws`), flag it — the schema's
`superRefine` requires SigV4 auth for snapshot creation on managed
clusters. (AWS-managed only applies to ES/OS targets; Solr sources
are never AWS-managed in this sense.)

## Probe — Solr

```
GET /solr/admin/info/system              # version, mode (cloud/standalone)
GET /solr/admin/collections?action=LIST  # SolrCloud
GET /solr/admin/cores?action=STATUS      # standalone
# Per collection/core:
GET /solr/<coll>/schema
GET /solr/<coll>/schema/fields
GET /solr/<coll>/select?q=*:*&rows=3     # sample docs
GET /solr/<coll>/select?q=*:*&rows=0     # numFound for doc count
```

Version from `/solr/admin/info/system.lucene.solr-spec-version`
(e.g. `9.7.0`). Schema form: `"SOLR 9.7.0"` (literal uppercase `SOLR`).

## What to record

For each cluster, remember:

- version + engine (for config)
- total doc count per index/collection
- field inventory with types: text, keyword, numeric, date, geo, nested
- analyzers on text fields (default + per-field)
- one or two **real tokens from real docs** — you will reuse these for
  relevancy tests in Phase 5
- anything unusual: custom analyzers, update request processors (Solr),
  plugin-dependent mappings (ES), ingest pipelines

## Pick your test indices

Choose 1–3 indices/collections for deep validation in Phase 5. Good picks:

- have multiple field types (text + keyword + numeric at minimum)
- have enough docs that query results are meaningful (>100)
- are not `_internal` / `.kibana` / `.ql-*` bookkeeping

## Checkpoint 1

Print a compact summary to the user:

```
Source: <engine> <version> at <url>, <N> indices totaling <M> docs
Target: <engine> <version> at <url>, currently <K> indices

Will migrate: [<idx1>, <idx2>, ...]
Deep-validate:  [<idx1>, <idx2>]      # subset for Phase 5
Auth model:   source=<basic|sigv4|mtls>, target=<...>
Run mode:     snapshot-only | snapshot+replay

Proceed? (yes / edit scope / change auth / ...)
```

Wait for explicit confirmation before Phase 2.
