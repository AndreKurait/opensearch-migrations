# Phase 0 — Schema refresh

Goal: anchor this run to the live schema, not your memory.

## Steps

1. Confirm the migration-console pod is up:
   ```
   kubectl -n ma get pod migration-console-0 -o jsonpath='{.status.phase}'
   ```
   Expect `Running`. If not, stop and report to the user.

2. Pull the JSON Schema:
   ```
   kubectl exec -n ma migration-console-0 -- cat /root/schema/workflowMigration.schema.json \
     > migrationCompanion/runs/<ts>/schema.json
   sha256sum migrationCompanion/runs/<ts>/schema.json \
     | awk '{print $1}' > migrationCompanion/runs/<ts>/schema.sha256
   ```

3. Read the schema. It is a **single-line minified JSON file** — do NOT
   use `head`, `less`, or line-index tools on it; use `jq` to extract
   what you need:
   ```
   jq '.properties | keys' migrationCompanion/runs/<ts>/schema.json
   jq '.properties.snapshotMigrationConfigs' migrationCompanion/runs/<ts>/schema.json
   jq '.$defs | keys' migrationCompanion/runs/<ts>/schema.json
   jq '.. | .pattern? // empty' migrationCompanion/runs/<ts>/schema.json | sort -u
   ```
   Specifically internalize:
   - the **version regex** (`ES`/`OS`/`SOLR` literal + version digits)
   - required vs optional fields at the top level
   - what a minimal `snapshotMigrationConfigs[].perSnapshotConfig` looks like
   - auth shapes (`basic` / `sigv4` / `mtls`)
   - cross-field `superRefine` rules visible as `x-zod-*` annotations or
     described in each property's `description`

4. Also pull the sample for comparison:
   ```
   kubectl exec -n ma migration-console-0 -- workflow configure sample \
     > migrationCompanion/runs/<ts>/sample-from-pod.yaml
   ```
   Use this only as a loose starting shape. The schema wins on any
   conflict — samples drift.

## Checkpoint 0 (silent)

Log the schema fingerprint:

```
Schema fingerprint: sha256:<first-12-chars>  (from /root/schema/workflowMigration.schema.json)
```

No user interaction needed unless the schema failed to read.

## Common pitfalls

- Do **not** rely on your training data for the schema. It **will** be
  wrong on at least one field by the time you read this.
- The schema is **minified (single line)**. Running `head`, `tail`, or
  `sed -n '1,200p'` shows you essentially nothing, or misleadingly shows
  you one enormous truncated string. Always go through `jq`.
- The version regex is strict and literal. `Solr 8.11` fails; `SOLR 8.11.0`
  passes. Always include at least one `.N` segment.
- If the JSON Schema file is missing (old image), fall back to piping
  candidate configs through `workflow configure edit --stdin` and reading
  stderr for Zod errors — noisier but still ground truth.
