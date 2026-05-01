#!/usr/bin/env python3
"""
validate_plan.py — validate migration-plan.json against the schema and
against soft rules that the JSON Schema cannot express.

Returns exit 0 on success, 1 on any error. Prints findings to stderr.

Dependencies: jsonschema (pip install jsonschema). Falls back to structural
checks if the module is unavailable so the skill still works in minimal envs.
"""
from __future__ import annotations
import json
import sys
import re
import argparse
from pathlib import Path
from typing import Any

SCHEMA_PATH = Path(__file__).resolve().parent.parent / "schemas" / "migration-plan.schema.json"

# PF1 (iter-0): MA composes CR names like
#   <srcAlias>.<tgtAlias>.<snapshotName>.migration-<n>.<stage>
# These must collectively remain RFC-1123 <=253 chars and a-z0-9-
RFC1123 = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

# Known engine+version shapes accepted by MA's source version registry.
VERSION_PREFIXES = {"elasticsearch": "ES", "opensearch": "OS", "solr": "SOLR"}


def load_schema() -> dict[str, Any] | None:
    try:
        return json.loads(SCHEMA_PATH.read_text())
    except Exception as e:
        print(f"WARN: could not load schema {SCHEMA_PATH}: {e}", file=sys.stderr)
        return None


def validate(plan: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    schema = load_schema()
    if schema is not None:
        try:
            import jsonschema  # type: ignore

            try:
                jsonschema.validate(plan, schema)
            except jsonschema.ValidationError as e:
                errors.append(f"schema: {e.message} at ${'/'.join(str(p) for p in e.absolute_path)}")
        except ImportError:
            # Best-effort structural checks without jsonschema.
            for required in ("version", "source", "target", "mode"):
                if required not in plan:
                    errors.append(f"schema: missing required key '{required}'")

    # Soft rules beyond the JSON Schema.
    name = plan.get("name")
    if name is not None and not RFC1123.match(name):
        errors.append(f"name '{name}' is not RFC-1123 (a-z 0-9 -).")

    snap = (plan.get("source") or {}).get("snapshot") or {}
    snap_name = snap.get("name")
    if snap_name and not RFC1123.match(snap_name):
        errors.append(
            f"source.snapshot.name '{snap_name}' must be RFC-1123 — "
            "MA composes '<src>.<tgt>.<snapshotName>.migration-0.<stage>' "
            "and underscores break CR creation (PF1 from iter-0)."
        )

    # BYOS sanity
    if snap.get("alreadyExists") and not snap.get("name"):
        errors.append("source.snapshot.alreadyExists=true requires source.snapshot.name")
    if snap.get("alreadyExists") and not snap.get("repoUri"):
        errors.append("source.snapshot.alreadyExists=true requires source.snapshot.repoUri")

    # Live mode requires an endpoint
    src = plan.get("source") or {}
    if not snap.get("alreadyExists") and not src.get("endpoint"):
        errors.append("Live-source mode requires source.endpoint (or switch to BYOS via source.snapshot.alreadyExists=true).")

    # Version registry
    engine = (src.get("engine") or "").lower()
    if engine and engine not in VERSION_PREFIXES:
        errors.append(f"source.engine '{engine}' not one of {sorted(VERSION_PREFIXES)}")

    return errors


def format_version_token(engine: str, version: str) -> str:
    """'elasticsearch' + '7.10.2' -> 'ES 7.10.2' (the form MA expects)."""
    prefix = VERSION_PREFIXES.get(engine.lower())
    if not prefix:
        raise ValueError(f"unknown engine: {engine}")
    return f"{prefix} {version}".strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("plan", type=Path)
    args = ap.parse_args()
    plan = json.loads(args.plan.read_text())
    errs = validate(plan)
    if errs:
        for e in errs:
            print(f"✗ {e}", file=sys.stderr)
        return 1
    print("✓ plan is valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
