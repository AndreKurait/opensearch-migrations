# Pitfalls

This file is deliberately short. The agent is expected to **discover
problems dynamically** — by reading the live JSON Schema at
`/root/schema/workflowMigration.schema.json`, probing cluster state
with `kubectl`/`curl`, reading `workflow submit` / `workflow status`
error messages, and inspecting the report's own output. If a problem
can be surfaced that way, it does not belong in this file.

Only four kinds of things stay here:

1. **Meta-discipline** the agent must hold (e.g. always re-read the
   schema).
2. **Schema-vs-runtime divergences** where the schema permits an input
   but the runtime rejects it with an error that points elsewhere, so
   the agent can't trace the failure back to the bad field.
3. **Silent-accept fields** where the schema permits an input, the
   runtime ignores it, and there is no error at all — the agent cannot
   discover it by trying.
4. **Non-technical policy** the agent has no way to infer.

Everything else — version-string format, TLS flags, namespace-scoped
secrets, SigV4 requirements, log-filtering tips, relevancy-drift
calibration, round-trip feature gaps — is discoverable. Let the agent
discover and report it.

## Schema drift

The JSON Schema at `/root/schema/workflowMigration.schema.json` changes
between releases. Your training data is stale. Always `cat` the live
file in Phase 0 and trust it over anything else — including the sample
YAML, which itself drifts behind the schema. If a field you "know"
exists is rejected, look at the live schema first.

## targetClusters silently accepts a version field

`sourceClusters.<name>` requires `version`; `targetClusters.<name>` does
not accept one. The target entry schema omits `additionalProperties`,
so JSON Schema validation **silently accepts** a pasted `version:` line
and the workflow just ignores it. The target version is inferred by
the workflow at runtime from the live cluster. This is not
discoverable from an error message — the agent must know not to write
it.

## Cluster names: schema regex is looser than the runtime

`sourceClusters.<name>` and `targetClusters.<name>` pass JSON Schema
validation with hyphens (`my-src`, `os-tgt`) but then fail at submit
time with a Zod error pointing at `snapshotMigrations[N].targetConfig.label`
and pattern `^[a-zA-Z0-9_]+$`. The error does not name the cluster-key
field, so the agent can't trace the failure back to the cluster name
without this note. Use alphanumerics + underscores only.

## perSnapshotConfig labels must be RFC 1123 (lowercase)

The `label` field under `perSnapshotConfig.<snapshot>[N]` accepts mixed
case per the schema, but it becomes part of a Kubernetes resource name
(`<src>-<tgt>-<snapshot>-<label>`). Kubernetes then rejects the
SnapshotMigration CR with "must consist of lower case alphanumeric
characters". Like the cluster-name case above, the error points at the
derived resource name, not the source field. Use lowercase letters and
digits only. No hyphens, no underscores, no camelCase.

## Do not reference proprietary Elastic naming

In committed text (reports, commits, PRs, code comments), say
"Elasticsearch" only when technically necessary (referring to a
specific version or wire protocol). Never cite Elastic Inc. marketing
copy or copy from elastic.co docs. Companion is parallel to, not
derived from, upstream Elastic work.

## Deployment gotchas the agent cannot infer from the schema

These are deterministic runtime failures that repeat across every
fresh demo run and have nothing to do with user input. Keep the list
short — only entries whose root cause is invisible from the schema
and whose error message does not name the real field belong here.

### Port-forwards vs in-cluster workflow pods

Both the ES and Solr demos expose port-forwarded endpoints on the host
(`https://localhost:19200`, `http://localhost:18983`, etc.) for agent
probing. Those hostnames **do not resolve** inside the cluster — the
migration-console pod runs its snapshot / backfill workflows as
separate pods in namespace `ma`, and `localhost:19200` inside those
pods is the pod's own loopback. Scaffolded `workflow` configs must use
the in-cluster service DNS (`elasticsearch-master.ma.svc:9200`,
`opensearch-cluster-master.ma.svc:9200`) even though the agent probed
through `localhost:...`. The Zod schema does not know about
port-forwards; this must be substituted at scaffold time.

### Localstack S3 endpoint shape

The `snapshotInfo.repos.<name>.endpoint` field accepts any URL. In the
demo, S3 is served by localstack in-cluster at
`localstack.ma.svc.cluster.local:4566`, addressed as
`localstack://localstack.ma.svc.cluster.local:4566` (not
`http://`). Using `http://...` passes schema validation but the
workflow then fails with an opaque `AccessDenied` from the
localstack-injected STS mock because the scheme triggers real-AWS
auth resolution. The `localstack://` scheme is the signal the
workflow uses to switch credential providers. Always use it in demo
runs.

### `docker compose` v2 plugin, not `docker-compose` v1

The Solr sandbox's `run.sh` and `docker-compose.yml` require compose
v2 (`docker compose ...` as a docker CLI plugin). Hosts with only
the legacy `docker-compose` Python tool fail with
`docker: 'compose' is not a docker command`. Install to
`~/.docker/cli-plugins/docker-compose` (pinning to a recent release;
older binaries have a `lfstack.push invalid packing` Go-runtime
panic on kernels with 48-bit virtual addresses). The demo script
checks `docker compose version` and exits early if missing.

### Translation-shim image needs a registry on :5001

`TrafficCapture/transformationShim` is built with `./gradlew
jibDockerBuild`, which tags the image `localhost:5001/migrations/
transformation_shim`. The Solr sandbox's `docker-compose.yml`
references that exact tag. If no registry is running on :5001,
compose fails with `pull access denied`. Either start a local
registry (`docker run -d -p 127.0.0.1:5001:5000 --name
registry-5001 registry:2`) or re-tag the image locally
(`docker tag localhost:5001/migrations/transformation_shim:latest
migrations/transformation_shim:latest` — the sandbox's run.sh does
this automatically as a fallback). The demo script starts the
registry if it isn't already up.

### Shim FileSystem-sink reports live in docker named volumes

`shim-reports-os` and `shim-reports-solr` are docker named volumes,
not bind mounts. You cannot `ls` or `cp` them from the host
directly. Extraction from inside a one-shot container:

```
docker run --rm -v shim-reports-os:/src -v "$(pwd):/dst" \
  alpine sh -c 'cp -r /src/. /dst/shim-reports-os/'
```

An agent that "doesn't see the reports" on the host has almost
always forgotten this step, not run into a sink bug.

### Solr source: auto-detection and collection discovery

The orchestrator's `CreateSnapshot` step does **not** require the
companion to set `sourceType: solr` or enumerate
`solrCollections: [...]` in `config.yaml`. Two auto-behaviors cover the
common case:

1. `ClusterVersionDetector` reads `sourceCluster.version` (the
   `"SOLR x.y.z"` string you produced from the probe) and infers
   `--source-type=solr` from the literal prefix.
2. `SolrBackupStrategy.discoverCollections()` uses the SolrCloud
   Collections API when the collection list is empty.

This is why the scaffolded YAML for a Solr source looks almost
identical to an ES or OS source. If you find yourself hand-editing
`config.yaml` to inject Solr-only fields, re-check whether the field
is actually required — in the common case it isn't.

What you **do** need for a Solr source:

- A backup repository registered on the SolrCloud side before the
  workflow starts. For the in-tree demo clusters this is the
  `localstack-s3` repository wired by `valuesSolrSource.yaml`. For
  production, coordinate with the Solr operators ahead of time;
  `snapshotInfo.repos.<name>.s3RepoPathUri` alone is not enough if
  the Solr cluster itself has no matching repository.
- Solr 8+ on SolrCloud (the discoverCollections path assumes Cloud
  mode via the Collections API). Standalone Solr works only with
  explicit `solrCollections` hand-configuration, which is an edge
  case the companion doesn't currently cover.

### Solr Operator CRDs must be applied before the testClusters chart

The `test-clusters` umbrella chart declares a `SolrCloud` CR but its
packaged `solr-operator` subchart does **not** include the
`solr.apache.org` CRDs (upstream convention: CRDs ship as a separate
manifest). A fresh `helm install` with `valuesSolrSource.yaml` fails
with:

```
resource mapping not found for name: "solr-source" ...
no matches for kind "SolrCloud" in version "solr.apache.org/v1beta1"
```

Apply the CRDs once per cluster before the chart:

```
kubectl apply --server-side -f \
  https://solr.apache.org/operator/downloads/crds/v0.9.1/all-with-dependencies.yaml
```

**Second gotcha:** the operator bundle also contains
`zookeeperclusters.zookeeper.pravega.io`, which the helm
zookeeper-operator subchart already claims ownership of. Your first
helm upgrade after `kubectl apply` of the CRDs fails with
`label validation error: missing key "app.kubernetes.io/managed-by"`.
Hand that specific CRD back to helm before retrying:

```
kubectl label crd zookeeperclusters.zookeeper.pravega.io \
  app.kubernetes.io/managed-by=Helm --overwrite
kubectl annotate crd zookeeperclusters.zookeeper.pravega.io \
  meta.helm.sh/release-name=tc \
  meta.helm.sh/release-namespace=ma --overwrite
```

The demo script (`demo/solr-backfill.sh`) does both steps
automatically. Version must match the `solr-operator` subchart
version in `deployment/k8s/charts/aggregates/testClusters/Chart.yaml`.

### Zookeeper operator image: pravega 0.2.15 is abandoned

The `solr-operator` 0.9.1 subchart pins
`pravega/zookeeper-operator:0.2.15` (April 2023, last release).
On hosts with 48-bit virtual-address kernels the Go runtime panics
on startup with `lfstack.push invalid packing` and the operator
crash-loops before reconciling anything. The symptom is a Ready
SolrCloud CR that never gets a ZK ensemble — `kubectl get zk`
returns nothing and the operator pod's logs show only the panic.

Use the active `adobe/zookeeper-operator` fork instead. Override
in a companion-demo values overlay (see
`valuesCompanionDemo.yaml`):

```
solr-operator:
  zookeeper-operator:
    image:
      repository: adobe/zookeeper-operator
      tag: 0.2.15-adobe-20260423
      pullPolicy: IfNotPresent
```

This is demo-infrastructure-only; it is not a companion-skill
concern and does not belong in `valuesSolrSource.yaml` (which is
shared with other test-cluster consumers).

### Zookeeper operator needs coordination.k8s.io/leases RBAC

Even on a working image, the 0.9.1 chart's namespaced `Role` for
the ZK operator omits `coordination.k8s.io/leases` verbs. The
operator requires them for leader election on startup and logs
`leases.coordination.k8s.io is forbidden` indefinitely without
ever reconciling. Patch post-helm (idempotent):

```
kubectl patch role tc-zookeeper-operator --type=json \
  -p='[{"op":"add","path":"/rules/-","value":{
       "apiGroups":["coordination.k8s.io"],
       "resources":["leases"],
       "verbs":["get","list","watch","create","update","patch","delete"]}}]'
kubectl rollout restart deploy/tc-zookeeper-operator
```

The demo script does this after every `helm upgrade` because helm
re-renders the Role and drops the patch.

### SolrCloud CR sometimes missing after `helm upgrade`

A known race: `helm get manifest tc` shows the `SolrCloud`
resource in the rendered output, but `kubectl get solrcloud` is
empty immediately after a successful `helm upgrade` when the
`solr.apache.org` CRDs were `kubectl apply`d outside helm's
lifecycle. Workaround is to re-apply the CR from the rendered
manifest:

```
helm get manifest tc | awk \
  '/^# Source: test-clusters\/charts\/solrSource\/templates\/solrcloud.yaml/,/^---$/' \
  > /tmp/solrcloud.yaml
kubectl apply -f /tmp/solrcloud.yaml
```

The demo script does this unconditionally after upgrade. Not
discoverable from helm output (helm reports success; only a
`kubectl get` reveals the missing CR).

### Solr S3 backup: parent prefix must exist as an S3 object

When the workflow's `createSnapshot` step POSTs a Solr BACKUP
action, Solr's `S3BackupRepository` rejects the call if the
**parent prefix** under the configured bucket does not exist as an
S3 key. The error message reads:

```
specified location s3:///<collection>/<snapshot>/ does not exist
```

The leading triple-slash in that string is Solr's internal URI
rendering when the parent check fails; **the bucket name is not
actually dropped** and the orchestrator's URI construction is
correct. The fix is to pre-create a zero-byte marker for the
collection prefix:

```
aws --endpoint-url=http://localstack.ma.svc.cluster.local:4566 \
    --region=us-east-1 \
    s3api put-object --bucket solr-backups --key <collection>/
```

This applies to any S3-compatible backend (localstack, MinIO,
real S3) because the check is in Solr, not in the backend. The
agent should detect the `does not exist` error, identify the
collection prefix from the scaffolded `s3RepoPathUri`, create the
marker, and resubmit.

### OpenSearch cluster disk watermarks reject allocation on busy demo hosts

Metadata migration creates indices on the target; if the demo
host filesystem sits above the default `cluster.routing.allocation.disk.watermark.low`
(85%) the target refuses to allocate shards and the backfill step
hangs with `index … has exceeded the disk watermark`. Demo hosts
frequently sit at 85-95% because of unrelated build artifacts.
Raise the watermarks before submit:

```
curl -sk -u admin:*** -XPUT \
  -H 'Content-Type: application/json' \
  https://opensearch-cluster-master.ma.svc:9200/_cluster/settings \
  -d '{"persistent":{
        "cluster.routing.allocation.disk.watermark.low":"97%",
        "cluster.routing.allocation.disk.watermark.high":"98%",
        "cluster.routing.allocation.disk.watermark.flood_stage":"99%"}}'
```

This is a demo-host compensation, not a production recommendation.

### Solr copyField destinations land unpopulated on target

Solr schemas commonly define `copyField` directives that fan a
text field into a `string`-typed sibling (e.g.
`name → name_str`) for exact-match or sort use cases. The metadata
migrator correctly translates the destination field to a
`keyword` mapping on the target, but RFS transfers documents
**as emitted by the source** — not as re-indexed. The
destination field therefore exists in the target mapping but
contains no data. This is not a bug; it is the correct behavior
given that copyField is a Solr index-time mechanic.

Caller-side options the report should surface:

- Point aggregations/sorts at the populated source field
  (`category`, `name`) using `.keyword` sub-fields or a runtime
  script.
- Run an `update_by_query` on the target to copy values into the
  destination fields post-migration.

Flag the unpopulated fields in the structural-parity section of
the report so the user knows which dashboards / saved queries to
rewrite.

### Sort-order drift on string IDs between Solr and OpenSearch

Queries that sort on a `string`/`keyword` field containing
numeric-looking values (e.g. doc ids `P-1, P-2, ..., P-100`) can
produce **different top-N orderings** between Solr and
OpenSearch even when the full result set is identical. Two
mechanisms in play, at least one of which will bite any given
query:

- **Missing `sort` clause on the Solr side.** A Solr query without
  an explicit `sort=` and with only a filter clause falls back to
  internal Lucene doc-id order (insertion order). OpenSearch's
  default sort for the equivalent query body is relevance score,
  then `_doc`. Different defaults, different top-N.
- **Lexicographic vs natural ordering.** Both engines sort
  `keyword`/`string` fields byte-lexicographically
  (`P-1, P-13, P-17, ..., P-5, P-53, ...`) when an explicit sort
  is given. Users often *expect* natural numeric order
  (`P-1, P-2, P-3, ...`) because the leading zero-padding is
  missing, and any pre-existing Solr query that happened to
  return the "nice" order was relying on insertion-order
  fallback rather than the sort itself.

The report should call this out as an **ordering drift** (not a
parity failure) whenever a `sort: [id]` or equivalent query
shows set-equal hits but different top-N. Recommend to callers:
sort on a dedicated numeric field, or zero-pad string ids at
index time.
