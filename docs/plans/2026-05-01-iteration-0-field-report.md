# Iteration-0 Field Report: End-to-End MA Migration on kind

Date: 2026-05-01
Cluster: `ma` (kind, single node, real `opensearch-migrations` helm chart + test clusters)
Source: Elasticsearch 7.10.2 (helm chart `tc` → `elasticsearch-master-0`)
Target: OpenSearch 2.11.1 (helm chart `tc` → `opensearch-cluster-master-0`)
Snapshot store: LocalStack S3 at `s3://migrations-default-123456789012-dev-us-east-2`
MA orchestration: Argo Workflows v4.0.3, `full-migration-imported-clusters` WorkflowTemplate

--------------------------------------------------------------------------------

## Goal of this iteration

The point of Migration Companion's pre-migration phase is to run a real MA
migration against a real (or sampled) source + real OpenSearch target BEFORE
the customer commits to production. This run is that, on a synthetic fixture,
so the resulting skill can tell future customers:

  1. what steps actually happen end to end
  2. which of those steps fail first for which reasons
  3. what parity signals to check on success
  4. which divergences are expected/benign vs real regressions

--------------------------------------------------------------------------------

## What worked (evergreen pipeline shape — use as the skill backbone)

The workflow-template is a 3-step wrapper:

  generate-migration-configs  →  configureAndSubmitWorkflow  →  monitorWorkflow

`configureAndSubmitWorkflow` is the important one: it runs
`node /root/configProcessor/index.js initialize ...` which transforms the
user-facing JSON (source-configs[] + target-config) into a set of
Kubernetes CRs in `migrations.opensearch.org/v1alpha1`:

  - SnapshotMigration  (one per source×target×snapshot×migration tuple)
  - ApprovalGate       (multiple, named <...>.{vapretry,evaluatemetadata,migratemetadata})

Those CRs trigger a second Argo workflow (name: `migration-workflow`) which
does the actual per-step orchestration. The observed step sequence was:

```
upsertSnapshotMigrationResource
runMetadata                      # pre-eval (dry-run metadata plan)
  [WAIT: ApprovalGate evaluatemetadata → Approved]
runMetadata                      # real metadata migrate
  [WAIT: ApprovalGate migratemetadata → Approved]
createRfsCoordinatorSecret
createRfsCoordinatorService
createRfsCoordinatorStatefulSet
startHistoricalBackfill          # spawns rfs-<hash>-* worker deployment
runMigrationCommandForStatus     # poll until backfill done
stopHistoricalBackfill
deleteRfsCoordinatorStatefulSet
deleteRfsCoordinatorService
deleteRfsCoordinatorSecret
patchSnapshotMigrationCompleted
```

Elapsed: ~4 min from workflow submit to `phase=Succeeded` on a 115-doc fixture.
Kafka / Proxy / (create) Snapshot / Traffic-Replayer branches were all
correctly Skipped (we configured BYOS + backfill-only).

**Parity on the target (source vs target):**

| Index          | _count src | _count tgt | mapping | settings           | query   |
|----------------|-----------:|-----------:|---------|--------------------|---------|
| products       |         50 |         50 | MATCH   | MATCH              | MATCH   |
| events-2025.05 |         25 |         25 | MATCH   | MATCH              | MATCH   |
| legacy_orders  |         40 |         40 | MATCH   | MATCH              | MATCH   |

A `range` + `avg` aggregation query on `products` returned identical
hits (33, `eq`) and identical avg (99.99) on both clusters.

--------------------------------------------------------------------------------

## What stumbled (empirical pitfalls — every one of these should surface as a Companion pre-flight check or narrated step)

### PF1. Snapshot name containing underscore → entire workflow fails at CR-apply

**First run (`iter0-byos-7t7gz`) failed** because my snapshot was named
`iter0_fixture`. The config-processor builds CR names of the form:

    source<N>.target<M>.<snapshotName>.migration-<I>.<stage>

So `iter0_fixture` produced:

    source1.target1.iter0_fixture.migration-0.evaluatemetadata

which Kubernetes rejects: underscore violates RFC 1123 subdomain rules.

    The ApprovalGate "source1.target1.iter0_fixture.migration-0.migratemetadata"
    is invalid: metadata.name: Invalid value: "source1.target1.iter0_fixture...":
    a lowercase RFC 1123 subdomain must consist of lower case alphanumeric
    characters, '-' or '.'

Four CRs (3 ApprovalGate + 1 SnapshotMigration) rejected, workflow Failed.

**Companion pre-flight rule (MUST have):** before handing a snapshot name to
the workflow, validate against `^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$` AND the
composite CR name fits inside 253 chars. If the user's existing snapshot
has an underscore, offer to take a fresh snapshot with a legal name,
because renaming an in-repository S3 snapshot is painful.

This is a **root-cause empirical finding** that has nothing to do with
ES↔OS parity and would never show up in a static "compatibility" pre-check.
It only surfaces by actually running configureAndSubmitWorkflow.

### PF2. ApprovalGates are blocking by default — workflows hang indefinitely without an approver

Three ApprovalGate CRs (`evaluatemetadata`, `migratemetadata`, `vapretry`)
block the pipeline until someone patches their `/status` subresource to
`phase: Approved`. There is **no out-of-the-box auto-approver** and
`migration-console` CLI on the running image does not expose an approve
command.

In iteration-0 I manually ran:

    kubectl patch approvalgate.migrations.opensearch.org/<name> \
      --subresource=status --type=merge -p '{"status":{"phase":"Approved"}}'

**Companion narration rule:** the Companion must either (a) auto-approve
after summarizing the evaluation output for the user, (b) hand the user
a single command per gate with a one-line description of what they are
approving, or (c) both. The current UX of "workflow just sits in Running"
with no surfaced prompt is bad. This is not a bug — it's a deliberate
approval-first design — but the Companion **is** the thing that makes it
tolerable.

### PF3. `migration-console` CLI on k8s image is intentionally minimal

On the `migration-console-0` pod, `console --help` shows only:

    Commands: clusters | completion | kafka

i.e. no `workflow`, `snapshot`, `backfill`, `metadata`, or `approve`
subcommands. Those all live inside Argo + CRDs now. The Companion
should **not** ship a "call `console X`" flow — the future is
`kubectl get/patch` against MA CRDs, or `argo submit` against
WorkflowTemplates.

### PF4. No `argo` binary inside `migration-console-0`

I expected to `kubectl exec migration-console-0 -- argo submit` — it
doesn't exist there. Submissions happen via `kubectl create -f <wf.yaml>`
from the host, OR the Argo Workflows UI. The Companion's kubectl-based
flow is fine; just don't depend on argo CLI in the console image.

### PF5. No `source-creds` / `target-creds` secret pre-created

The helm chart creates `elasticsearch-master-credentials` for the source
test cluster, but the workflow's `basic_auth.k8s_secret_name` field
expects a secret with `username` and `password` keys (named whatever).
I had to `kubectl create secret generic source-creds ...` and
`target-creds` manually.

**Companion step:** before submitting, ensure the secrets named in
`source-configs[].source.authConfig.basic.secretName` and
`target-config.basic_auth.k8s_secret_name` actually exist in the
MA namespace, with `username` + `password` keys. If they don't,
create them (prompting the user for creds) or fail loud.

### PF6. `_count` vs `_cat/indices` "docs.count" always disagree when IDs repeat

My seed script bulk-indexed `events-2025.05` 75 times but with only 25
unique `_id`s (bug in my seed — 3 full passes of the same 25-doc set).
Source `_count` = 25, source `_cat/indices docs.count` = 75 (counts pre-merge
segment writes). Target showed **identical** 25/75 after the migration.

**Companion parity rule:** use `_count` for "logical doc parity" and
compare `docs.count` only as a secondary segment-level signal
(don't alert on it unless `_count` also diverges). The Companion's
parity check must NOT flag segment-count drift on its own.

### PF7. Target index settings gain `replication.type: DOCUMENT` — benign, must be on allowlist

`products` on target has an extra setting not present on source:

    "replication": {"type": "DOCUMENT"}

This is a default OpenSearch adds on index creation. It is NOT a
regression. Companion settings-diff must have an explicit
"expected drift" allowlist that includes at least:

    uuid, creation_date, provided_name, version, routing,
    replication.type, history.uuid

Otherwise every single migrated index will trip a false "settings changed"
alarm.

### PF8. runMigrationCommandForStatus pod shows "Error" transiently — not a failure

Status poller pod `runmigrationcommandforstatus-511983504` appears as
**Error** throughout the workflow even though the workflow succeeds.
This is because Argo's `retryStrategy` interprets `exit 1` ("still
running, re-poll me") as a retryable failure. It's noise, but if the
Companion shows raw `kubectl get pods` output the user will panic.

**Companion UX rule:** when summarizing workflow progress, **group**
pods by template and show only the **latest attempt's** state.
Do not surface "status poller Error" as a warning.

### PF9. (From earlier, non-migration) kindTesting.sh needs buildkit DNS + inotify bumps

Pre-existing environmental gotchas, already captured in my memory and
the plan doc but worth re-flagging for the v1 skill:

  - `fs.inotify.max_user_instances` must be raised before kind create
    (typical default 128 is insufficient).
  - Corp/egress-restricted hosts need `buildImages/buildkitd.toml`
    with `[dns] nameservers = [...]` pointing at corp DNS;
    otherwise buildkit sandbox falls back to 8.8.8.8 and hangs on
    `dnf install` for minutes with no clear error.

Both should be in the Companion's "setup preflight" checklist before
it even tries to start a kind cluster.

--------------------------------------------------------------------------------

## Mappings to the plan (docs/plans/2026-04-30-migration-companion-unified-ux.md)

Evidence that supports or refines specific claims in the plan:

- **Plan §"Empirical assessment is the differentiator":** CONFIRMED. 8 of 9
  findings above (PF1–PF8) are invisible from static introspection of the
  source cluster. Only PF9 is static-catchable.
- **Plan §"Run real MA helm chart":** CONFIRMED as the right call. A
  library shortcut that bypassed Argo + CRDs would not have surfaced PF1
  (CR name validation), PF2 (ApprovalGates), or PF8 (retry-loop noise).
- **Plan §"Graceful opt-out for huge clusters":** the 4-minute elapsed on
  a 115-doc fixture suggests per-doc overhead is dominated by the fixed
  Argo/K8s/RFS spin-up. A 10M-doc cluster would add ~minutes for doc-
  copy but the orchestration overhead should be constant. The Companion
  should estimate based on this rather than extrapolating linearly.
- **Plan §"migration-plan.json as first-class artifact":** strongly
  reinforced. The config-processor's user-config → CR expansion is the
  exact place where migration-plan.json should feed in, and PF1 proves
  we must validate that JSON against K8s naming rules BEFORE submit.
- **Plan §"Top-K overlap + error-free query parity":** the mapping/settings
  /query matrix above is exactly the right shape. PF6 (count ambiguity)
  and PF7 (expected settings drift) tell us the parity check needs
  guardrails, not a blunt "diff everything".

--------------------------------------------------------------------------------

## Artifacts on disk

- `/tmp/kind-iter0-logs/kindTesting.log` — helm chart first-run
- `/tmp/kind-iter0-logs/kindTesting-run2.log` — helm chart second run (success)
- `/tmp/kind-iter0-logs/seed2.out` — fixture bulk-index + snapshot output
- `/tmp/kind-iter0-logs/wf.yaml` — workflow spec submitted (BYOS)
- `/tmp/kind-iter0-logs/watch_child.log` — full per-10s child-workflow log
- `/tmp/kind-iter0-logs/kubeconfig2` — kubeconfig for the kind cluster

--------------------------------------------------------------------------------

## Recommended next iterations (not yet done)

1. **Iter-1:** rerun with `source.endpoint` populated (live-access mode)
   so we exercise the "take-a-snapshot-for-me" branch, not just BYOS.
2. **Iter-2:** force a known-bad mapping (e.g. `join` type, multi-type
   mappings from ES6) on the source, watch `runMetadata` pre-eval
   report errors, and capture the exact shape of that error surface.
   This is the hard-mode of the Companion's pre-migration value.
3. **Iter-3:** try with MA chart `.Values.source.version: ES_2_4` +
   a synthetic ES 2.4 snapshot to confirm the version matrix the plan
   claims to support actually lands rows in OS 2.x.
4. **Skill authoring:** codify PF1–PF9 as a `companion-runbook` skill
   whose structure mirrors §"skill directory" in the plan.
