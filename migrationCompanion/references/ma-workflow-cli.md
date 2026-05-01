# Migration Assistant Workflow CLI — Companion Reference

The authoritative CLI reference lives in this repo at:

- `kiro-cli/kiro-cli-config/steering/workflow.md`

Read that file for the full command surface, safety notes, and
monitoring patterns. This file is a companion-specific quick reference —
just the commands the companion agent uses.

## Pod exec shape

```bash
kubectl exec -n ma migration-console-0 -- <cmd>        # non-interactive
kubectl exec -i -n ma migration-console-0 -- <cmd>     # with stdin
```

## Commands companion uses

```bash
# Phase 0 — schema
kubectl exec -n ma migration-console-0 -- cat /root/schema/workflowMigration.schema.json
kubectl exec -n ma migration-console-0 -- workflow configure sample

# Phase 3 — configure + submit
cat config.yaml | kubectl exec -i -n ma migration-console-0 -- \
    workflow configure edit --stdin
kubectl exec -n ma migration-console-0 -- workflow configure view
kubectl exec -n ma migration-console-0 -- workflow configure clear
kubectl exec -n ma migration-console-0 -- workflow submit

# Phase 3/4 — watch
kubectl exec -n ma migration-console-0 -- workflow status
kubectl exec -n ma migration-console-0 -- workflow status <name>
kubectl exec -n ma migration-console-0 -- workflow output <name>

# Approvals (only after explicit user yes)
kubectl exec -n ma migration-console-0 -- workflow approve
```

## Things companion does NOT do

- Does not run `argo submit` directly.
- Does not `kubectl apply` Argo Workflow manifests.
- Does not modify the `ma` namespace RBAC, service accounts, CRDs, or
  operator configuration.
- Does not modify AWS resources (domains, IAM, security groups) —
  see the ⛔ list at the top of `kiro-cli/.../workflow.md` and follow
  that guidance: surface the required command and ask.

## Schema file path

```
/root/schema/workflowMigration.schema.json        inside migration-console pod
                                        (ENV: WORKFLOW_SCHEMA_FILENAME)
```

The same schema is also published at GitHub Releases as
`workflowMigration.schema.json` per release tag, useful pre-deploy.
