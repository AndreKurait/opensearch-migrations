#!/usr/bin/env python3
"""
emit_workflow.py — consume migration-plan.json and emit a ready-to-submit
Argo Workflow YAML that references MA's `full-migration-imported-clusters`
WorkflowTemplate.

The shape we emit is the USER-facing `source-configs` array (pre-jq). The
workflow template's `generate-migration-configs` step transforms it into the
CR-level NORMALIZED schema. Two code paths, driven by `plan.source.snapshot`:

  createSnapshot (default, product path):
    - source.endpoint        = real HTTPS URL for MA to reach the cluster
    - source.version         = "ES 7.10.2" / "OS 2.11.0" / ...
    - source.basic_auth      = { k8s_secret_name: ... }   (if plan has auth)
    - NO source.snapshotRepo (workflow uses the s3-snapshot-configmap)
    - snapshot-and-migration-configs[0] has NO snapshotConfig
      (workflow auto-inserts {"testsnapshot": {..., "createSnapshotConfig": {}}})

  BYOS (advanced, reuse pre-existing snapshot):
    - source.endpoint        = ""  (MA does NOT connect to the source cluster)
    - source.version         = still required (controls RFS reader code path)
    - NO source.basic_auth   (no cluster to auth against)
    - source.snapshotRepo    = { s3RepoPathUri, awsRegion, [endpoint] }
    - snapshot-and-migration-configs[0].snapshotConfig.snapshotNameConfig
        .externallyManagedSnapshotName = plan.source.snapshot.name
"""
from __future__ import annotations
import json
import sys
import argparse
from pathlib import Path
from typing import Any

# Reuse validator helpers
sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_plan import validate, format_version_token  # noqa: E402


def build_source_configs(plan: dict[str, Any]) -> list[dict[str, Any]]:
    src = plan["source"]
    engine = src["engine"]
    version_token = format_version_token(engine, src.get("version", ""))
    snap = src.get("snapshot") or {}
    byos = bool(snap.get("alreadyExists") and snap.get("name"))

    if byos:
        # BYOS: MA does NOT reach the source cluster. Leave endpoint empty,
        # omit basic_auth entirely, and pin the snapshot repo on source.
        source_obj: dict[str, Any] = {
            "endpoint": "",
            "version": version_token,
        }
        if snap.get("repoUri"):
            source_obj["snapshotRepo"] = {
                "s3RepoPathUri": snap["repoUri"],
                "awsRegion": snap.get("awsRegion", "us-east-1"),
            }
            if snap.get("s3Endpoint"):
                source_obj["snapshotRepo"]["endpoint"] = snap["s3Endpoint"]

        snapshot_config = {
            "snapshotNameConfig": {
                "externallyManagedSnapshotName": snap["name"],
            }
        }
    else:
        # createSnapshotConfig path: MA reaches the source cluster, runs
        # snapshot creation on its own via the configmap-backed S3 repo.
        source_obj = {
            "endpoint": src.get("endpoint", ""),
            "version": version_token,
        }
        if src.get("allowInsecure"):
            source_obj["allow_insecure"] = True
        auth = src.get("auth") or {}
        if auth.get("mode") == "basic" and auth.get("secretName"):
            source_obj["basic_auth"] = {"k8s_secret_name": auth["secretName"]}
        # No source.snapshotRepo, no snapshotConfig — workflow synthesizes both
        # from s3-snapshot-configmap and hardcoded "testsnapshot" key.
        snapshot_config = None

    # Migrations list per mode.
    mode = plan.get("mode", "full-backfill")
    pass_cfg: dict[str, Any] = {}
    if mode in ("full-backfill", "metadata-only"):
        pass_cfg["metadataMigrationConfig"] = {}
    if mode == "full-backfill":
        rfs_workers = (plan.get("scope") or {}).get("rfsWorkers", 1)
        pass_cfg["documentBackfillConfig"] = {"podReplicas": rfs_workers}
    migrations: list[dict[str, Any]] = [pass_cfg] if pass_cfg else []

    smc_entry: dict[str, Any] = {"migrations": migrations}
    if snapshot_config is not None:
        smc_entry["snapshotConfig"] = snapshot_config

    return [{
        "source": source_obj,
        "snapshot-and-migration-configs": [smc_entry],
    }]


def build_target_config(plan: dict[str, Any]) -> dict[str, Any]:
    tgt = plan["target"]
    out: dict[str, Any] = {"endpoint": tgt["endpoint"]}
    if tgt.get("allowInsecure"):
        out["allow_insecure"] = True
    auth = tgt.get("auth") or {}
    if auth.get("mode") == "basic" and auth.get("secretName"):
        out["basic_auth"] = {"k8s_secret_name": auth["secretName"]}
    # NOTE: target schema does NOT accept a version field (jq `del(.version)`).
    return out


def render_workflow(plan: dict[str, Any], generate_name: str | None = None,
                    namespace: str | None = None) -> dict[str, Any]:
    gen = generate_name or f"{plan.get('name', 'migration')}-"
    ns = namespace or (plan.get("empirical") or {}).get("namespace", "ma")

    source_configs = build_source_configs(plan)
    target_config = build_target_config(plan)

    return {
        "apiVersion": "argoproj.io/v1alpha1",
        "kind": "Workflow",
        "metadata": {"generateName": gen, "namespace": ns},
        "spec": {
            "workflowTemplateRef": {"name": "full-migration-imported-clusters"},
            "arguments": {
                "parameters": [
                    {"name": "source-configs",
                     "value": json.dumps(source_configs, indent=2)},
                    {"name": "target-config",
                     "value": json.dumps(target_config, indent=2)},
                    {"name": "monitor-retry-limit", "value": "33"},
                ]
            },
        },
    }


def to_yaml(doc: dict[str, Any]) -> str:
    try:
        import yaml  # type: ignore

        class _LiteralStr(str):
            pass

        def _literal_representer(dumper, data):
            return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")

        yaml.SafeDumper.add_representer(_LiteralStr, _literal_representer)

        # Force literal-block for multi-line strings so Argo parameter values stay readable.
        def _walk(o):
            if isinstance(o, dict):
                return {k: _walk(v) for k, v in o.items()}
            if isinstance(o, list):
                return [_walk(v) for v in o]
            if isinstance(o, str) and "\n" in o:
                return _LiteralStr(o)
            return o

        return yaml.safe_dump(_walk(doc), sort_keys=False, default_flow_style=False)
    except ImportError:
        return _hand_yaml(doc)


def _hand_yaml(doc: dict[str, Any], indent: int = 0) -> str:
    pad = "  " * indent
    lines: list[str] = []
    for k, v in doc.items():
        if isinstance(v, dict):
            lines.append(f"{pad}{k}:")
            lines.append(_hand_yaml(v, indent + 1))
        elif isinstance(v, list):
            lines.append(f"{pad}{k}:")
            for item in v:
                if isinstance(item, dict):
                    first = True
                    for ik, iv in item.items():
                        if first:
                            prefix = f"{pad}- "
                            first = False
                        else:
                            prefix = f"{pad}  "
                        if isinstance(iv, str) and "\n" in iv:
                            lines.append(f"{prefix}{ik}: |")
                            for ln in iv.splitlines():
                                lines.append(f"{pad}    {ln}")
                        elif isinstance(iv, (dict, list)):
                            lines.append(f"{prefix}{ik}:")
                            lines.append(_hand_yaml({ik: iv}, indent + 2).split(f"{ik}:\n", 1)[-1])
                        else:
                            lines.append(f"{prefix}{ik}: {_scalar(iv)}")
                else:
                    lines.append(f"{pad}- {_scalar(item)}")
        elif isinstance(v, str) and "\n" in v:
            lines.append(f"{pad}{k}: |")
            for ln in v.splitlines():
                lines.append(f"{pad}  {ln}")
        else:
            lines.append(f"{pad}{k}: {_scalar(v)}")
    return "\n".join(ln for ln in lines if ln != "")


def _scalar(v: Any) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    s = str(v)
    if any(c in s for c in ":#{}[],&*!|>'\"%@`") or s.strip() != s:
        return json.dumps(s)
    return s


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--generate-name", default=None)
    ap.add_argument("--namespace", default=None)
    ap.add_argument("--skip-validate", action="store_true")
    args = ap.parse_args()

    plan = json.loads(args.plan.read_text())
    if not args.skip_validate:
        errs = validate(plan)
        if errs:
            for e in errs:
                print(f"✗ {e}", file=sys.stderr)
            return 1

    wf = render_workflow(plan, args.generate_name, args.namespace)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(to_yaml(wf))
    print(f"✓ wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
