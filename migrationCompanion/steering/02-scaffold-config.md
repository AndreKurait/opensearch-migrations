# Phase 2 — Scaffold config against the JSON Schema

Goal: produce a `config.yaml` that passes Zod validation on the first or
second try, filled in only where this migration needs it.

## Inputs

- Live schema at `runs/<ts>/schema.json`
- Sample at `runs/<ts>/sample-from-pod.yaml` (hint only)
- Probe output from Phase 1

## Construction order

Walk the schema's `OVERALL_MIGRATION_CONFIG` shape top-down and fill
**only** the fields this migration actually needs. For a standard
snapshot-only migration the shape is roughly:

```yaml
skipApprovals: true
sourceClusters:
  source:
    endpoint: https://...
    allowInsecure: true          # only if self-signed
    version: "ES 7.10.2"         # exact schema form ("ES x.y.z", "OS x.y.z", or "SOLR x.y.z")
    authConfig: { basic: { secretName: source-creds } }
    snapshotInfo:
      repos:
        repo1:
          awsRegion: us-east-2
          s3RepoPathUri: s3://.../source
      snapshots:
        snap1:
          repoName: repo1
          config:
            createSnapshotConfig: { ... }   # or externallyManagedSnapshotName
targetClusters:
  target:
    endpoint: https://...
    allowInsecure: true
    version: "OS 2.11.0"
    authConfig: { basic: { secretName: target-creds } }
snapshotMigrationConfigs:
  - fromSource: source
    toTarget:   target
    perSnapshotConfig:
      snap1:
        - label: primary
          metadataMigrationConfig: {}
          documentBackfillConfig: {}
```

Snapshot-only means **omit** `kafkaClusterConfiguration` and `traffic`.

## Rules the schema enforces (re-verify in the live schema each run)

- `version` matches `^(?:ES [125678]|OS [123]|SOLR [89])(?:\.[0-9]+)+$`
- Each `snapshotMigrationConfigs[i].fromSource` must be a key in
  `sourceClusters`; same for `toTarget`.
- Each `perSnapshotConfig` key must exist in that source's
  `snapshotInfo.snapshots`.
- Every `USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG` entry must have
  **at least one** of `metadataMigrationConfig` or
  `documentBackfillConfig`.
- AWS-managed source + `createSnapshotConfig` → SigV4 auth required, and
  the referenced repo must have a non-empty `s3RoleArn`.
- Replayer's `removeAuthHeader: true` conflicts with target having
  `basic` or `sigv4` auth.

## Solr sources

A Solr source uses the **same** top-level shape above — same
`sourceClusters`, same `snapshotMigrationConfigs`, same workflow CLI.
The orchestrator auto-detects `--source-type=solr` from the `version:
"SOLR …"` string, and `CreateSnapshot` auto-discovers collections via
the SolrCloud Collections API when the collection list is empty. No
Solr-specific fields in `config.yaml` are required for the common case.

Differences to note when filling the YAML for a Solr source:

- `version:` takes the literal `"SOLR 9.7.0"` form (uppercase `SOLR`,
  Lucene spec version from `/solr/admin/info/system`).
- `allowInsecure: true` only if the endpoint is HTTPS with a self-signed
  cert. Plain-HTTP Solr needs `endpoint: http://...` and no
  `allowInsecure`.
- `snapshotInfo.repos.<name>.s3RepoPathUri` points at an S3 location
  that the Solr pods can write to (backup repository must be
  pre-registered in Solr's config, e.g. via
  `solrSource.backupRepositories` in the test-clusters chart, or via
  the Solr Operator's `backupRepositories` field in production).
- `documentBackfillConfig: {}` is supported end-to-end for Solr — the
  backfill path reads the Solr backup's Lucene segments directly and
  reindexes into the OpenSearch target.
- `metadataMigrationConfig: {}` performs best-effort schema translation
  (Solr fields → OpenSearch mappings) through the migration-utilities
  translation shim. Review the generated target mapping during Phase 4
  — Solr-specific types (e.g. `pdate`, `text_general` with custom
  tokenizers) map to reasonable OpenSearch equivalents but may need
  per-index tweaking.

Restrict to a subset of collections with an explicit index-allowlist
transformation when the user asks for a partial migration; otherwise
every collection on the source is migrated.

## Validate before submit

Do NOT apply to the live session blindly. First dry-run the YAML through
the pod:

```
cat config.yaml | kubectl exec -i -n ma migration-console-0 -- \
  workflow configure edit --stdin
```

Read stderr for Zod errors. Each error has a `path:` — fix that exact
field, don't restructure. Cap retries at 3; if still invalid, stop and
show the user the error.

## Local save

Write the final accepted YAML to `runs/<ts>/config.yaml` and keep a
running diff vs. the sample in the report.

## Checkpoint 2

Show the user:

1. the final `config.yaml` (or a diff vs. the sample)
2. per non-default field, a one-line rationale ("allowInsecure: true
   because probe saw self-signed cert at target")

Ask: "Apply this config and prepare secrets? (yes / edit / change X)"
