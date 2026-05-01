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
