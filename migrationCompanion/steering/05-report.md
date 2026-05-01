# Phase 6 — Report

Goal: produce `runs/<ts>/report.md` that is both a human-readable
summary and a complete reproducer.

## Sections, in order

### 1. Summary
One paragraph, plain English. "Migrated <engine> <version> at <src> to
OS <version> at <tgt>. <N> indices, <M> docs. Structural parity ✓.
Query-shape battery: <k/n> within noise. Relevancy showcase: <k/n>
top-5 identical. [Any notable flags in one sentence.]"

### 2. Reproduction
A fenced block with the exact commands to replay this migration:

```bash
# Schema fingerprint: sha256:<12-char-prefix>
# (if the current schema differs, re-run may produce a different config)

cat <<'YAML' | kubectl exec -i -n ma migration-console-0 -- workflow configure edit --stdin
<paste runs/<ts>/config.yaml here>
YAML
kubectl exec -n ma migration-console-0 -- workflow submit
```

Include any `kubectl create secret generic …` commands the run needed
(**without** the actual credentials — show `--from-literal=username=[REDACTED]`).

### 3. Schema fingerprint
```
sha256: <full-hex>     file: /root/.workflowUser.schema.json
```

Note if it matches a prior run in this repo's `runs/` directory.

### 4. Probe snapshots
Source before / target before / target after.

For ES/OS: engine, version, index count, doc count, representative field
types.

For Solr source: version, mode, collection list, per-collection field
count.

Keep this tight — the raw JSON lives in the same `runs/` dir.

### 5. Structural parity
Markdown table, one row per index. Columns:

```
| Index | Source docs | Target docs | Mapping diff | Verdict |
```

Any non-trivial mapping diff goes in a `<details>` block beneath the
table.

### 6. Query-shape battery
For each query, one `<details>` block:

```
<details>
<summary>q3_phrase_match_title — within noise</summary>

Query (source / target):
```json
…
```

| metric | source | target | delta |
| ... |

Verdict: <one sentence, cite the numbers>.
</details>
```

### 7. Relevancy showcase
One section per showcase query:

```
#### Query: "laptop"

| Rank | Source ID | Source title | Source score | Target ID | Target title | Target score | Match |
|------|-----------|--------------|--------------|-----------|--------------|--------------|-------|
| 1    | …         | …            | 9.12         | …         | …            | 9.08         | ✓     |
| ...  |

Commentary: <one paragraph, hypothesis not verdict>.
```

If all three showcase queries had identical top-5 sets: one sentence is
enough. "All 3 showcase queries returned identical top-5 sets with
score drift under 3%. No narrative."

### 8. Incompatibilities flagged
Things the probe surfaced that didn't block the migration but the user
should know:

- custom Solr `<updateRequestProcessor>` chains with no OS equivalent
- ES plugins referenced in mappings that OS may not support
- ingest pipelines the source used that the user needs to port
- analyzer differences that may explain relevancy drift

Each as a bullet with a concrete next step.

### 9. Run artifacts
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
└── report.md              (this file)
```

## Rules

- **Never include credentials.** Secrets appear as `[REDACTED]`
  everywhere. Check the final file before writing.
- **Never claim success that parity didn't demonstrate.** If doc counts
  don't match, the summary says "Migration completed but structural
  parity failed; see section 5."
- **Keep the report committable.** Under ~500 lines target. Push long
  query bodies into `<details>` blocks. The run dir has the raw data.
