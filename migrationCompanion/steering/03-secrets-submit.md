# Phase 3 — Secrets, submit, watch

## Ensure secrets exist

For each `authConfig.basic.secretName` (and `mtls.clientSecretName`,
etc.) referenced in the config:

```
kubectl -n ma get secret <name> >/dev/null 2>&1 || NEED=$name
```

If missing, prompt the user **per secret**:

```
Secret "<name>" does not exist in namespace ma. Create it now?
Username: <prompt>
Password: <prompt, masked>
```

Create via:

```
kubectl -n ma create secret generic <name> \
  --from-literal=username=<u> \
  --from-literal=password=<p>
```

Never echo the password to stdout or the report.

## Solr pre-submit backup (Solr sources only)

If any source in the config has `version: "SOLR ..."`, you must create
the backup **before** `workflow submit`. The orchestrator's
`CreateSnapshot` step assumes Elasticsearch and will not produce a
usable Solr backup (tracked as a separate orchestrator change).

### 1. Verify the backup repository is registered on Solr

Do not guess bucket/prefix URIs. Ask Solr what it knows:

```
kubectl -n ma exec <solr-pod> -- \
  curl -sS "http://localhost:8983/solr/admin/cores?action=STATUS&wt=json" \
  | jq '.status | keys'

kubectl -n ma exec <solr-pod> -- \
  curl -sS "http://localhost:8983/api/cluster" \
  | jq '.cluster.properties'
```

For an S3-backed backup, Solr must already have an
`S3BackupRepository` entry in `solr.xml` (or `coreContainer` cluster
properties). If no such repo is registered, **stop and report to the
user** — the companion does not silently configure Solr repositories;
that is a source-cluster operator decision.

If the user has a repo registered, capture:

- `REPO_NAME` — the `name` attribute on the `<repository>` element
- `REPO_BUCKET` / `REPO_PREFIX` — inferred from repo config, then confirmed

### 2. Issue the backup via Solr Collections API

One `BACKUP` call per collection, not one big global call:

```
BACKUP_NAME="ma-$(date -u +%Y%m%d-%H%M%S)"
for COLL in $(collections to migrate); do
  kubectl -n ma exec <solr-pod> -- \
    curl -sS -u "$SOLR_USER:$SOLR_PASS" \
      "http://localhost:8983/solr/admin/collections" \
      --data-urlencode "action=BACKUP" \
      --data-urlencode "collection=${COLL}" \
      --data-urlencode "name=${BACKUP_NAME}_${COLL}" \
      --data-urlencode "repository=${REPO_NAME}" \
      --data-urlencode "async=${BACKUP_NAME}_${COLL}" \
  | jq
done
```

`async=<id>` is not optional for large collections — without it the HTTP
call blocks past most curl timeouts and Solr still runs the backup but
you lose the return handle.

### 3. Poll REQUESTSTATUS until each completes

```
for COLL in ...; do
  while true; do
    STATE=$(kubectl -n ma exec <solr-pod> -- \
      curl -sS "http://localhost:8983/solr/admin/collections?action=REQUESTSTATUS&requestid=${BACKUP_NAME}_${COLL}&wt=json" \
      | jq -r '.status.state')
    case "$STATE" in
      completed) break ;;
      failed)    echo "Solr BACKUP failed for ${COLL}"; exit 1 ;;
      *)         sleep 10 ;;
    esac
  done
done
```

### 4. Record the backup name in the workflow config

The value of `externallyManagedSnapshotName` in `config.yaml` must be
`${BACKUP_NAME}_${COLL}` (one `snapshotInfo.snapshots[...]` entry per
collection). If you have multiple collections, you have multiple
snapshot entries and multiple `perSnapshotConfig` entries pointing at
them.

**Do not** run any Java `CreateSnapshot` or `RfsMigrateDocuments`
binary directly. The only CLI surfaces the companion uses are
`kubectl`, `curl`, `jq`, and `workflow`.

## Save the session

```
cat runs/<ts>/config.yaml | kubectl exec -i -n ma migration-console-0 -- \
  workflow configure edit --stdin
kubectl exec -n ma migration-console-0 -- workflow configure view
```

`view` should round-trip your YAML cleanly. If it rewrites anything,
read carefully — Zod defaults may have filled in optional fields.

## Submit

```
kubectl exec -n ma migration-console-0 -- workflow submit
```

`submit` returns a workflow name (e.g. `migration-workflow-abcde`).
Record it in the run dir.

## Checkpoint 3 (auto, streaming)

Watch with exponential backoff, not a tight loop:

```
# Start at 15s, then 30, 60, 120, 300 (cap at 5min).
kubectl exec -n ma migration-console-0 -- workflow status <name> \
  2>&1 | grep -E 'Phase:|WAITING|Running|Succeeded|Failed'
```

For long steps, poll the per-component logs directly:

```
# RFS backfill progress (minimal tail):
kubectl logs -n ma -l app.kubernetes.io/name=reindex-from-snapshot --tail=5 \
  | grep -o 'Doc Number [0-9]*'

# Metadata migration full output (worth reading):
kubectl logs -n ma -l workflows.argoproj.io/workflow=<name> --tail=100 \
  | grep -A 50 'Starting Metadata'
```

Surface phase transitions to the user: "snapshot created ✓", "metadata
migration running…", "document backfill 40% (Doc 200000)", etc.

## Approvals

If the workflow has an approval gate (`skipApprovals: false`), pause and
**ask the user before calling `workflow approve`**. First fetch the
step's output so the user has context:

```
kubectl exec -n ma migration-console-0 -- workflow output <name>
```

Then prompt:

```
Approval gate reached at step <step>. Output above.
Approve to continue? (yes / no / show more output)
```

Only call `workflow approve` after explicit `yes`.

## Terminal state

When `workflow status <name>` reports `Succeeded` or `Failed`, do **not**
declare success yet. Proceed to Phase 4 (structural parity) and Phase 5
(query/relevancy). Success is a Phase 5 decision, not a Phase 3
decision.

## Pitfalls

- `workflow configure edit --stdin` silently overwrites the default
  session. If the user had something saved, it's gone. Warn first.
- A `Failed` workflow often has the real error one or two steps
  upstream of the failure marker. Fetch `workflow output <name>`
  interactively and walk the steps.
- `workflow submit` is idempotent only if the session config is
  byte-identical — otherwise it creates a new workflow. Don't re-submit
  by reflex on a transient error.
