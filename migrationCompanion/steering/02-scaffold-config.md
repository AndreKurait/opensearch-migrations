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
    version: "ES 7.10.2"         # exact schema form
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

## Solr sources: `externallyManagedSnapshotName` only

For `SOLR <version>` sources, **do not** use `createSnapshotConfig`. The
workflow's `CreateSnapshot` template is built for Elasticsearch/OpenSearch
snapshots and renders `--source-type elasticsearch` unconditionally; on a
Solr cluster that either fails outright or produces an unusable layout.

Until the orchestrator grows a Solr branch (tracked separately), the
companion drives Solr backups out-of-band via Solr REST and then points
the workflow at that backup by name. In the config:

```yaml
sourceClusters:
  source:
    endpoint: http://solr-svc:8983
    version: "SOLR 8.11.4"
    authConfig: { basic: { secretName: source-creds } }
    snapshotInfo:
      repos:
        repo1:
          awsRegion: us-east-2
          s3RepoPathUri: s3://<bucket>/<prefix>   # bucket must already be
                                                   # registered on Solr as
                                                   # an S3BackupRepository;
                                                   # agent verifies, does
                                                   # NOT invent a prefix
      snapshots:
        snap1:
          repoName: repo1
          config:
            externallyManagedSnapshotName: <BACKUP_NAME>   # the name the
                                                            # agent passed
                                                            # to Solr BACKUP
```

The Solr backup itself is created in Phase 3 (see `03-secrets-submit.md`,
"Solr pre-submit backup"), not here. This phase just records the
`externallyManagedSnapshotName` that Phase 3 will produce.

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
