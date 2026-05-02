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
