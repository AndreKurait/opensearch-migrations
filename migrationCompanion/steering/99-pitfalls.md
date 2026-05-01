# Pitfalls

Things that have bitten people. Check this list when something's weird.

## Schema drift

The JSON Schema at `/root/schema/workflowMigration.schema.json` changes between
releases. Your training data is stale. Always `cat` the live file in
Phase 0 and trust it over anything else — including the sample YAML,
which itself drifts behind the schema. If a field you "know" exists is
rejected, look at the live schema first.

## Version string format

The regex is `^(?:ES [125678]|OS [123]|SOLR [89])(?:\.[0-9]+)+$`. Strict
rules:

- Engine prefix is literal: `ES` / `OS` / `SOLR`. `Solr` lowercase fails.
- Major version is restricted: ES {1,2,5,6,7,8}, OS {1,2,3}, SOLR {8,9}.
  ES 3 or 4 won't parse (no such releases). SOLR 7 won't parse.
- At least one `.N` segment required. `ES 7.10` fails; `ES 7.10.2`
  passes.

## TLS self-signed certs

If probe sees a self-signed cert on either cluster (common for local
kind setups and corporate clusters), set `allowInsecure: true` on that
cluster in the config. The migration workers honor this flag; curl
needs `-k` independently.

## Secrets are namespaced

All `authConfig.basic.secretName` lookups happen in the `ma` namespace.
If the user created the secret in `default`, the migration fails at
submit with an opaque error. Always `kubectl -n ma get secret <name>`
before submit.

## AWS-managed source + createSnapshotConfig = SigV4 required

The schema enforces this in a `superRefine`. If the source endpoint
matches `*.es.amazonaws.com` or `*.aos.*.on.aws` and the config creates
a snapshot, you need `authConfig: { sigv4: { region: … } }` **and** the
referenced repo must have a non-empty `s3RoleArn`.

## workflow configure edit --stdin overwrites silently

The `default` session config is overwritten byte-for-byte on every
`--stdin` call. If the user had a saved config, it's gone. Warn before
overwriting.

## Approval gates

`skipApprovals: true` is fine for demos but dangerous for production.
Default to asking the user in the interview; if they say "fully
automated", `skipApprovals: true` it — but log a one-liner in the
report noting that no human gate fired.

## Long logs burn context

When watching progress, filter aggressively:

- RFS: `kubectl logs ... --tail=5 | grep -o 'Doc Number [0-9]*'`
- Metadata: `--tail=100 | grep -A 50 'Starting Metadata'`
- Workflow status: `grep -E 'Phase:|Running|Succeeded|Failed'`

Never dump the full log into the report or context window; link to the
kubectl command instead.

## Doc count mismatches are real errors

A 1-doc delta usually means the snapshot raced an indexer. A ≥1%
delta is a bug. Don't paper over it with "close enough" — investigate
via `workflow output <name>` and fix the config before re-running.

## Relevancy drift is normal and expected

BM25 parameters, analyzer versions, and tokenizer behaviors change
between ES 7/OS 1/OS 2/OS 3. Small score drift (≤5%) and occasional
rank swaps in positions 5–10 are expected. Report them, hypothesize a
cause, don't raise alarm.

## Solr → OpenSearch isn't a round-trip

SolrCloud-specific concepts (routing keys, request handlers,
updateRequestProcessorChain, velocity templates) have no OpenSearch
equivalent. The migration covers documents + mappings. Tell the user
explicitly when you see these features.

## targetClusters has no version field (schema asymmetry)

`sourceClusters.<name>` requires `version`; `targetClusters.<name>` does not
accept one. The target version is inferred by the workflow at runtime from
the live cluster. Don't copy-paste a `version` line from the source block
into the target block — it will be rejected as an unknown property.

## authConfig shape differs from intuition

It's `authConfig: { basic: { secretName: <name> } }`, not the nested
`basicAuth: { usernameFromSecret: { name, key } }` shape that some upstream
docs suggest. The referenced secret must contain keys `username` and
`password` literally. Verify with `kubectl -n ma get secret <name> -o
jsonpath='{.data}' | base64 -d` before submitting.

## Cluster names: schema regex is looser than the runtime

`sourceClusters.<name>` and `targetClusters.<name>` pass JSON Schema
validation with hyphens (`my-src`, `os-tgt`) but then fail at transform
time with a Zod error pointing at `snapshotMigrations[N].targetConfig.label`
and pattern `^[a-zA-Z0-9_]+$`. Use alphanumerics + underscores only
(`solrsrc`, `os_tgt`). The `workflow configure edit --stdin` validator
accepts the hyphenated form; the failure only surfaces at `workflow submit`.

## perSnapshotConfig labels must be RFC 1123 (lowercase)

The `label` field under `perSnapshotConfig.<snapshot>[N]` accepts mixed
case per the schema, but it becomes part of a Kubernetes resource name
(`<src>-<tgt>-<snapshot>-<label>`). Kubernetes then rejects the
SnapshotMigration CR with "must consist of lower case alphanumeric
characters". Stick to lowercase letters and digits. No hyphens, no
underscores, no camelCase.

## Externally-managed Solr snapshot requires snapshotInfo.repos

To point the workflow at a pre-created Solr S3 backup (e.g. produced by
the Jenkins `solr8xK8sLocalTestCover` path or the CreateSnapshot CLI),
declare both:

    sourceClusters.<src>.snapshotInfo.repos.<repoName>:
      awsRegion: <region>
      s3RepoPathUri: s3://<bucket>/<prefix>     # directory, not snapshot
      endpoint: localstack://localstack:4566    # for kind/localstack demos
    sourceClusters.<src>.snapshotInfo.snapshots.<snapshotName>:
      repoName: <repoName>
      config:
        externallyManagedSnapshotName: <snapshotName>

Then reference `<snapshotName>` as a key under
`snapshotMigrationConfigs[N].perSnapshotConfig`. The workflow will skip
the create-snapshot step and go straight to metadata evaluation. See
`demo/solr-externally-managed.yaml` for a full working example.

## Do not reference proprietary Elastic naming

In committed text (reports, commits, PRs, code comments), say
"Elasticsearch" only when technically necessary (referring to a specific
version or wire protocol). Never cite Elastic Inc. marketing copy or
copy from elastic.co docs. Companion is parallel to, not derived from,
upstream Elastic work.

## Solr snapshot backfill reports Completed with zero docs transferred

On a Solr source with an externally-managed snapshot (the
`snapshotInfo.repos` + `externallyManagedSnapshotName` path), the
workflow can finish every step Succeeded — metadata creates the target
indices with correct mappings, `startHistoricalBackfill` spins up the
RFS Deployment, `checkBackfillStatus` reports
`{status: Completed, shard_complete: 2, shard_total: 2}` — yet the
target indices end up with `docs.count=0`.

Cause (working hypothesis): the `checkBackfillStatus` template polls the
RFS coordinator (the work-queue OpenSearch StatefulSet) almost
immediately after `startHistoricalBackfill`. If the coordinator sees no
in-progress leases and an empty work queue it reports "Completed" even
though the worker Deployment's pods are still in `ContainerCreating`
and never had a chance to enqueue or claim any shards. A secondary
cause, distinguishable only by reading real worker logs, is the RFS
SolrSnapshotReader silently failing on a Solr-native backup layout
(different from the ES `index-N` repo format RFS was originally built
for).

Diagnosis signal for the agent: if post-migration
`target./<idx>/_count` returns `0` but the mapping is present and
non-empty, **do not** claim the migration succeeded. Mark backfill as
**failed** in the report, keep the metadata-side analysis (mapping
diff, field-type sanity, analyzer probes on the mapping), and point
the user at `solrMigrationDevSandbox/README.md` and
`AIAdvisor/skills/solr-opensearch-migration-advisor/` for the
Solr-specific backfill investigation path. Do not run Layer 2 query
battery or Layer 3 relevancy showcase on an empty target.

Argo does not persist RFS-worker Deployment pod logs (only workflow-step
pods get S3-archived). To capture them, patch the RFS Deployment
template's entrypoint with a short pre-sleep, or stream
`kubectl logs -f` from a tight loop before the pod terminates.
