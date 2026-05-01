#!/usr/bin/env python3
"""
probe_source.py — introspect a live source cluster (or a kubectl-port-forwarded
one) and emit a draft migration-plan.json the agent can iterate on.

This is the "point it at a cluster" entrypoint. It:
  1. Hits the root endpoint to identify engine + version.
  2. Lists indices/cores and their sizes.
  3. Flags known hard-mode features (parent-join, ES6 multi-type, custom sim,
     percolator, Solr managed schema, etc.).
  4. Writes plan.json to stdout (or --out path).

Usage:
  python3 probe_source.py \\
    --endpoint https://elastic:9200 \\
    --user admin --password admin --insecure \\
    --target-endpoint https://os:9200 --target-user admin --target-password admin \\
    --out plan.json

No live endpoint? Use --from-snapshot to build a plan for BYOS mode.

Dependencies: only the Python stdlib (urllib). Works in the migration-console
pod, in the agent's shell, or anywhere else.
"""
from __future__ import annotations
import argparse
import json
import ssl
import sys
import urllib.request
import urllib.error
import base64
import re
from pathlib import Path
from typing import Any

# ---- HTTP helpers (stdlib) -------------------------------------------------

def _http(url: str, user: str | None, pw: str | None, insecure: bool,
          method: str = "GET", timeout: int = 15) -> dict[str, Any]:
    req = urllib.request.Request(url, method=method)
    if user is not None:
        token = base64.b64encode(f"{user}:{pw or ''}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    req.add_header("Accept", "application/json")
    ctx = ssl.create_default_context()
    if insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            body = r.read().decode("utf-8", errors="replace")
            ct = r.headers.get("content-type", "")
            if "json" in ct or body.lstrip().startswith(("{", "[")):
                return {"ok": True, "status": r.status, "json": json.loads(body)}
            return {"ok": True, "status": r.status, "text": body}
    except urllib.error.HTTPError as e:
        return {"ok": False, "status": e.code, "error": e.reason,
                "body": e.read().decode("utf-8", errors="replace")[:500]}
    except Exception as e:
        return {"ok": False, "status": None, "error": str(e)}


# ---- Engine detection ------------------------------------------------------

def identify_engine(root: dict[str, Any]) -> tuple[str, str]:
    """Return (engine, version) from a root-endpoint JSON response."""
    j = root.get("json", {})
    # ES / OS: { "version": { "number": "...", "distribution": "opensearch"? } }
    v = j.get("version") or {}
    if isinstance(v, dict) and v.get("number"):
        dist = (v.get("distribution") or "").lower()
        if dist == "opensearch":
            return "opensearch", v["number"]
        return "elasticsearch", v["number"]
    # Solr: / returns SolrCloud admin payload; try /solr/admin/info/system instead.
    if "lucene" in str(j).lower() or "solr" in str(j).lower():
        return "solr", j.get("lucene", {}).get("solr-spec-version", "unknown")
    return "unknown", "unknown"


HARD_MODE_MAPPING_TYPES = {"join", "percolator", "alias", "rank_feature", "rank_features", "dense_vector"}

# System indices that don't start with '.' but should still be filtered from user-facing probes.
# PF10 (iter-0 followup): SearchGuard creates `searchguard`; ES 7 creates `.opendistro_*`;
# OpenSearch security creates `security-auditlog-*` / `.opendistro_security`.
SYSTEM_INDEX_RX = re.compile(
    r"^(searchguard|security-auditlog|\.opendistro|\.plugins|\.kibana|\.tasks|\.watches|\.monitoring|\.reporting|\.apm|\.fleet)"
)


def _is_system_index(name: str) -> bool:
    return name.startswith(".") or bool(SYSTEM_INDEX_RX.match(name))


def probe_es_os(endpoint: str, user: str | None, pw: str | None, insecure: bool) -> dict[str, Any]:
    findings: dict[str, Any] = {"indices": [], "warnings": [], "cluster": {}}
    root = _http(endpoint, user, pw, insecure)
    if not root.get("ok"):
        return {"reachable": False, "detail": root}
    engine, version = identify_engine(root)
    findings["engine"] = engine
    findings["version"] = version
    findings["reachable"] = True

    # Cluster health
    health = _http(f"{endpoint}/_cluster/health", user, pw, insecure)
    if health.get("ok"):
        findings["cluster"]["health"] = health["json"].get("status")
        findings["cluster"]["nodes"] = health["json"].get("number_of_nodes")

    # _cat/indices
    cat = _http(f"{endpoint}/_cat/indices?format=json&bytes=b&h=index,docs.count,store.size,pri,rep",
                user, pw, insecure)
    if cat.get("ok"):
        for row in cat["json"]:
            name = row.get("index")
            if not name or _is_system_index(name):
                continue
            findings["indices"].append({
                "name": name,
                "docs": int(row.get("docs.count") or 0),
                "bytes": int(row.get("store.size") or 0),
                "primaries": int(row.get("pri") or 0),
                "replicas": int(row.get("rep") or 0),
            })

    # Hard-mode mapping sniff
    mapping = _http(f"{endpoint}/_mapping", user, pw, insecure)
    if mapping.get("ok"):
        for idx_name, spec in mapping["json"].items():
            if _is_system_index(idx_name):
                continue
            m_root = spec.get("mappings", {})
            # ES 6 style has top-level doctype keys directly; ES 7+ has properties directly under mappings
            types_seen = [k for k in m_root.keys() if k not in ("properties", "_meta", "_source", "dynamic")]
            if types_seen and engine == "elasticsearch" and int(str(version).split(".")[0]) <= 6:
                findings["warnings"].append(f"Index {idx_name}: ES6 multi-type mapping ({types_seen}) — RFS flattens to one.")
            # Field-type scan
            props = m_root.get("properties", {})
            if not props and types_seen:
                props = (m_root.get(types_seen[0]) or {}).get("properties", {})
            _scan_mapping_types(idx_name, props, findings)

    return findings


def _scan_mapping_types(idx: str, props: dict[str, Any], findings: dict[str, Any], path: str = "") -> None:
    for field, spec in (props or {}).items():
        ft = (spec or {}).get("type")
        if ft in HARD_MODE_MAPPING_TYPES:
            findings["warnings"].append(
                f"Index {idx}: field '{path+field}' uses hard-mode type '{ft}' — verify metadata migration support."
            )
        sub = (spec or {}).get("properties")
        if isinstance(sub, dict):
            _scan_mapping_types(idx, sub, findings, f"{path}{field}.")


def probe_solr(endpoint: str, user: str | None, pw: str | None, insecure: bool) -> dict[str, Any]:
    findings: dict[str, Any] = {"cores": [], "warnings": [], "reachable": False}
    base = endpoint.rstrip("/")
    root = _http(f"{base}/solr/admin/info/system?wt=json", user, pw, insecure)
    if not root.get("ok"):
        return {"reachable": False, "detail": root}
    findings["reachable"] = True
    findings["engine"] = "solr"
    findings["version"] = (root["json"].get("lucene", {}) or {}).get("solr-spec-version", "unknown")
    cores = _http(f"{base}/solr/admin/cores?wt=json", user, pw, insecure)
    if cores.get("ok"):
        for name, c in (cores["json"].get("status") or {}).items():
            findings["cores"].append({
                "name": name,
                "docs": (c.get("index") or {}).get("numDocs", 0),
                "bytes": (c.get("index") or {}).get("sizeInBytes", 0),
            })
    findings["warnings"].append("Solr path is v1-best-effort — validate schema/solrconfig compatibility manually.")
    return findings


# ---- Plan drafter ----------------------------------------------------------

def draft_plan(src: dict[str, Any], tgt: dict[str, Any], args) -> dict[str, Any]:
    engine = src.get("engine", "elasticsearch")
    plan: dict[str, Any] = {
        "version": "1.0.0",
        "name": args.name or "companion-draft",
        "source": {
            "engine": engine,
            "version": src.get("version", "unknown"),
            "endpoint": args.endpoint,
        },
        "target": {
            "engine": "opensearch",
            "endpoint": args.target_endpoint,
        },
        "scope": {"indexPatterns": ["*"], "excludePatterns": [".*"], "rfsWorkers": 1},
        "mode": "full-backfill",
        "gates": {"autoApprove": bool(args.auto_approve)},
        "empirical": {"enabled": True, "namespace": args.namespace or "ma", "sampleSize": 0},
    }
    if args.source_secret:
        plan["source"]["auth"] = {"mode": "basic", "secretName": args.source_secret}
    if args.target_secret:
        plan["target"]["auth"] = {"mode": "basic", "secretName": args.target_secret}
    if args.insecure_target:
        plan["target"]["allowInsecure"] = True
    if tgt.get("version"):
        plan["target"]["version"] = tgt["version"]
    return plan


# ---- CLI -------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--endpoint", required=True, help="Source HTTP(S) endpoint")
    ap.add_argument("--user", default=None)
    ap.add_argument("--password", default=None)
    ap.add_argument("--insecure", action="store_true")
    ap.add_argument("--target-endpoint", required=True)
    ap.add_argument("--target-user", default=None)
    ap.add_argument("--target-password", default=None)
    ap.add_argument("--insecure-target", action="store_true")
    ap.add_argument("--source-secret", default=None)
    ap.add_argument("--target-secret", default=None)
    ap.add_argument("--namespace", default=None)
    ap.add_argument("--name", default=None)
    ap.add_argument("--auto-approve", action="store_true")
    ap.add_argument("--out", type=Path, default=None)
    ap.add_argument("--findings-out", type=Path, default=None)
    args = ap.parse_args()

    # Choose probe impl
    if args.endpoint.rstrip("/").endswith("/solr") or "/solr" in args.endpoint:
        src = probe_solr(args.endpoint, args.user, args.password, args.insecure)
    else:
        src = probe_es_os(args.endpoint, args.user, args.password, args.insecure)

    tgt = probe_es_os(args.target_endpoint, args.target_user, args.target_password, args.insecure_target)

    if not src.get("reachable"):
        print(f"✗ source unreachable: {src.get('detail')}", file=sys.stderr)
        return 2
    if not tgt.get("reachable"):
        print(f"✗ target unreachable: {tgt.get('detail')}", file=sys.stderr)
        return 2

    plan = draft_plan(src, tgt, args)

    findings = {"source": src, "target": tgt}
    if args.findings_out:
        args.findings_out.parent.mkdir(parents=True, exist_ok=True)
        args.findings_out.write_text(json.dumps(findings, indent=2))
        print(f"✓ findings -> {args.findings_out}", file=sys.stderr)

    text = json.dumps(plan, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text)
        print(f"✓ plan -> {args.out}", file=sys.stderr)
    else:
        print(text)
    # Summary for the agent to read
    n_idx = len(src.get("indices") or src.get("cores") or [])
    print(f"✓ source: {src['engine']} {src['version']} — {n_idx} user indices/cores, "
          f"{len(src.get('warnings', []))} warnings", file=sys.stderr)
    for w in src.get("warnings", [])[:10]:
        print(f"  ! {w}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
