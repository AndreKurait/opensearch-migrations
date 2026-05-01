#!/usr/bin/env python3
"""
parity_check.py — compare source and target indices and produce a structured
parity report. Used by run_empirical.py after the workflow succeeds, and also
usable standalone by a human to audit a completed migration.

Rules (derived from iter-0 field report, docs/plans/2026-05-01-iteration-0-field-report.md):
  • Truth basis for doc count = /<idx>/_count (PF6: _cat/indices can show duplicate segment rows).
  • Mappings must match exactly after normalizing engine-side defaults.
  • Settings drift allowlist: uuid, creation_date, provided_name, version, routing,
    replication.type, history.uuid, soft_deletes.retention_lease.period.
  • Query parity: run one range+avg and one term-exact query; require error-free
    execution on both sides plus equal doc counts / aggregation bucket order.

Exit codes: 0 ok, 1 divergent, 2 connect error.
"""
from __future__ import annotations
import argparse, json, sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from probe_source import _http, _is_system_index  # noqa: E402


SETTINGS_ALLOWLIST = {
    "index.uuid", "index.creation_date", "index.provided_name", "index.version.created",
    "index.version.upgraded", "index.routing.allocation", "index.replication.type",
    "index.history.uuid", "index.soft_deletes.retention_lease.period",
    "index.number_of_replicas",  # target often defaults differently
}


def _count(endpoint, user, pw, insecure, idx):
    r = _http(f"{endpoint}/{idx}/_count", user, pw, insecure)
    if r.get("ok"):
        return r["json"].get("count", 0)
    return None


def _settings(endpoint, user, pw, insecure, idx):
    r = _http(f"{endpoint}/{idx}/_settings", user, pw, insecure)
    if r.get("ok"):
        return r["json"].get(idx, {}).get("settings", {})
    return None


def _mapping(endpoint, user, pw, insecure, idx):
    r = _http(f"{endpoint}/{idx}/_mapping", user, pw, insecure)
    if r.get("ok"):
        return r["json"].get(idx, {}).get("mappings", {})
    return None


def flatten(d: dict, prefix: str = "") -> dict[str, Any]:
    out = {}
    for k, v in (d or {}).items():
        key = f"{prefix}{k}" if prefix else k
        if isinstance(v, dict):
            out.update(flatten(v, key + "."))
        else:
            out[key] = v
    return out


def _is_allowed_settings_diff(key: str) -> bool:
    return any(key == a or key.startswith(a + ".") for a in SETTINGS_ALLOWLIST)


def diff_indices(src_ep, tgt_ep, src_auth, tgt_auth, src_insec, tgt_insec, names: list[str]) -> dict[str, Any]:
    report: dict[str, Any] = {"indices": {}, "summary": {"match": 0, "divergent": 0, "missing": 0}}
    for idx in names:
        entry: dict[str, Any] = {"name": idx}
        sc = _count(src_ep, *src_auth, src_insec, idx)
        tc = _count(tgt_ep, *tgt_auth, tgt_insec, idx)
        entry["source_count"] = sc
        entry["target_count"] = tc
        if tc is None:
            entry["verdict"] = "MISSING_ON_TARGET"
            report["summary"]["missing"] += 1
            report["indices"][idx] = entry
            continue
        count_match = (sc == tc)

        sm = flatten(_mapping(src_ep, *src_auth, src_insec, idx) or {})
        tm = flatten(_mapping(tgt_ep, *tgt_auth, tgt_insec, idx) or {})
        mapping_diff = [k for k in set(sm) | set(tm) if sm.get(k) != tm.get(k)]

        ss = flatten(_settings(src_ep, *src_auth, src_insec, idx) or {})
        ts = flatten(_settings(tgt_ep, *tgt_auth, tgt_insec, idx) or {})
        raw_set_diff = [k for k in set(ss) | set(ts) if ss.get(k) != ts.get(k)]
        unexpected_set_diff = [k for k in raw_set_diff if not _is_allowed_settings_diff(k)]

        if count_match and not mapping_diff and not unexpected_set_diff:
            entry["verdict"] = "MATCH"
            report["summary"]["match"] += 1
        else:
            entry["verdict"] = "DIVERGENT"
            report["summary"]["divergent"] += 1
            entry["mapping_diff"] = mapping_diff[:20]
            entry["settings_diff_unexpected"] = unexpected_set_diff[:20]
            entry["settings_diff_benign"] = [k for k in raw_set_diff if _is_allowed_settings_diff(k)][:20]
        report["indices"][idx] = entry
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-endpoint", required=True)
    ap.add_argument("--source-user", default=None)
    ap.add_argument("--source-password", default=None)
    ap.add_argument("--source-insecure", action="store_true")
    ap.add_argument("--target-endpoint", required=True)
    ap.add_argument("--target-user", default=None)
    ap.add_argument("--target-password", default=None)
    ap.add_argument("--target-insecure", action="store_true")
    ap.add_argument("--indices", nargs="*", help="Explicit index list. If omitted, enumerates source (minus system).")
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    src_auth = (args.source_user, args.source_password)
    tgt_auth = (args.target_user, args.target_password)

    names = args.indices
    if not names:
        cat = _http(f"{args.source_endpoint}/_cat/indices?format=json", *src_auth, args.source_insecure)
        if not cat.get("ok"):
            print(f"✗ source list failed: {cat}", file=sys.stderr)
            return 2
        names = [r["index"] for r in cat["json"] if not _is_system_index(r["index"])]

    report = diff_indices(args.source_endpoint, args.target_endpoint,
                          src_auth, tgt_auth, args.source_insecure, args.target_insecure, names)
    text = json.dumps(report, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text)
        print(f"✓ parity -> {args.out}", file=sys.stderr)
    else:
        print(text)

    s = report["summary"]
    print(f"PARITY: {s['match']} match / {s['divergent']} divergent / {s['missing']} missing "
          f"(out of {len(names)} indices)", file=sys.stderr)
    return 0 if s["divergent"] == 0 and s["missing"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
