#!/usr/bin/env python3
"""
run_empirical.py — THE orchestrator. Given a migration-plan.json, it:

  1. Validates the plan (scripts/validate_plan.py)
  2. Emits a workflow.yaml (scripts/emit_workflow.py)
  3. Ensures the kube-level prerequisites exist:
       - namespace
       - source-creds / target-creds secrets (if auth mode=basic)
  4. Submits the Workflow via `kubectl create -f`
  5. Watches the workflow, auto-approving ApprovalGates if plan.gates.autoApprove
  6. On success, runs parity_check.py against source+target (via port-forward)
  7. Writes report.md + findings.json

Inputs required (via --plan):
  - source.endpoint + target.endpoint (port-forwarded or reachable from host)
  - Kubeconfig path (from plan.empirical.kubeconfig or --kubeconfig flag)

Outputs:
  --out/report.md
  --out/workflow.yaml
  --out/findings.json   # raw structured evidence
  --out/parity.json

Fails loudly. No silent success.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import subprocess
import sys
import time
import textwrap
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_plan import validate                              # noqa: E402
from emit_workflow import render_workflow, to_yaml              # noqa: E402
from parity_check import diff_indices                           # noqa: E402
from probe_source import _http, _is_system_index                # noqa: E402


# ---- kubectl shelling ------------------------------------------------------

def kubectl(args: list[str], kubeconfig: str, check: bool = True,
            capture: bool = True, input_text: str | None = None) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    env["KUBECONFIG"] = kubeconfig
    cmd = ["kubectl", *args]
    r = subprocess.run(cmd, env=env, capture_output=capture, text=True,
                       input=input_text, check=False)
    if check and r.returncode != 0:
        raise RuntimeError(f"kubectl failed: {' '.join(cmd)}\nstdout: {r.stdout}\nstderr: {r.stderr}")
    return r


def ensure_secret(kubeconfig: str, ns: str, name: str, user: str, pw: str) -> None:
    existing = kubectl(["get", "secret", "-n", ns, name, "-o", "name"], kubeconfig, check=False)
    if existing.returncode == 0:
        print(f"  = secret {ns}/{name} exists")
        return
    print(f"  + creating secret {ns}/{name}")
    kubectl(["create", "secret", "generic", "-n", ns, name,
             f"--from-literal=username={user}", f"--from-literal=password={pw}"], kubeconfig)


# ---- Workflow lifecycle ----------------------------------------------------

APPROVAL_GATE_GVR = "approvalgates.migrations.opensearch.org"
SNAPSHOT_MIGRATION_GVR = "snapshotmigrations.migrations.opensearch.org"


def preflight_cleanup(kubeconfig: str, ns: str) -> None:
    """Purge stale state that would cause a fresh run to collide.

    MA's create-snapshot path hardcodes the snapshot key to 'testsnapshot',
    which drives CR names like 'source1-target1-testsnapshot-migration-0'.
    Any stale CR of that name (left by a previous failed run) will cause
    `ma-lock-on-complete-policy` to deny writes on re-run. Same for old
    approval gates and finished workflows."""
    print("  + preflight cleanup")
    # Failed/finished workflows (keep the template)
    kubectl(["delete", "workflow", "-n", ns, "-l",
             "workflows.argoproj.io/completed=true", "--ignore-not-found=true"],
            kubeconfig, check=False)
    # Snapshotmigration CRs (they seal on complete)
    r = kubectl(["get", SNAPSHOT_MIGRATION_GVR, "-n", ns, "-o", "name"],
                kubeconfig, check=False)
    for line in (r.stdout or "").splitlines():
        line = line.strip()
        if line:
            kubectl(["delete", line, "-n", ns, "--ignore-not-found=true"],
                    kubeconfig, check=False)
    # Approval gates
    kubectl(["delete", APPROVAL_GATE_GVR, "-n", ns, "--all",
             "--ignore-not-found=true"], kubeconfig, check=False)


def submit_workflow(kubeconfig: str, wf_path: Path) -> str:
    r = kubectl(["create", "-f", str(wf_path), "-o", "name"], kubeconfig)
    name = r.stdout.strip().split("/")[-1]
    print(f"  + submitted workflow {name}")
    return name


def watch_workflow(kubeconfig: str, ns: str, wf_name: str,
                   auto_approve: bool, timeout_sec: int, log_path: Path) -> str:
    """Returns 'Succeeded' / 'Failed' / 'Timeout'. Polls every 5s."""
    start = time.monotonic()
    last_phase = ""
    approved: set[str] = set()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w") as log:
        while time.monotonic() - start < timeout_sec:
            # Poll parent workflow phase
            r = kubectl(["get", "workflow", "-n", ns, wf_name, "-o", "jsonpath={.status.phase}"],
                        kubeconfig, check=False)
            phase = r.stdout.strip() or "Pending"
            if phase != last_phase:
                msg = f"[{int(time.monotonic()-start):4d}s] workflow {wf_name} phase={phase}"
                print(f"  . {msg}")
                log.write(msg + "\n"); log.flush()
                last_phase = phase
            if phase in ("Succeeded", "Failed", "Error"):
                return phase
            # Also poll child (Migration CR) workflows so we follow the whole tree
            child = kubectl(["get", "workflow", "-n", ns, "-l", f"workflows.argoproj.io/parent-name={wf_name}",
                             "-o", "jsonpath={.items[*].status.phase}"], kubeconfig, check=False)
            # Approval gates
            if auto_approve:
                gates = kubectl(["get", APPROVAL_GATE_GVR, "-n", ns,
                                 "-o", "jsonpath={range .items[*]}{.metadata.name}={.status.phase}{\"\\n\"}{end}"],
                                kubeconfig, check=False)
                for line in (gates.stdout or "").splitlines():
                    if "=Initialized" in line:
                        gname = line.split("=")[0]
                        if gname and gname not in approved:
                            print(f"  + approving gate {gname}")
                            log.write(f"[approve] {gname}\n"); log.flush()
                            patch = '{"status":{"phase":"Approved"}}'
                            kubectl(["patch", APPROVAL_GATE_GVR + "/" + gname, "-n", ns,
                                     "--subresource=status", "--type=merge", "-p", patch],
                                    kubeconfig, check=False)
                            approved.add(gname)
            time.sleep(5)
    return "Timeout"


def collect_workflow_evidence(kubeconfig: str, ns: str, wf_name: str, out_dir: Path) -> dict[str, Any]:
    """Pull key evidence: phases, failed step messages, events, child wf status."""
    ev: dict[str, Any] = {"workflow": wf_name}
    r = kubectl(["get", "workflow", "-n", ns, wf_name, "-o", "json"], kubeconfig, check=False)
    if r.returncode == 0:
        wf = json.loads(r.stdout or "{}")
        st = wf.get("status", {}) or {}
        ev["phase"] = st.get("phase")
        ev["message"] = st.get("message")
        ev["startedAt"] = st.get("startedAt")
        ev["finishedAt"] = st.get("finishedAt")
        nodes = st.get("nodes", {}) or {}
        ev["failed_nodes"] = [
            {"name": n.get("displayName"), "type": n.get("type"), "phase": n.get("phase"),
             "message": n.get("message")}
            for n in nodes.values() if n.get("phase") in ("Failed", "Error")
        ]
    # Approval gate final states
    gates = kubectl(["get", APPROVAL_GATE_GVR, "-n", ns, "-o", "json"], kubeconfig, check=False)
    if gates.returncode == 0:
        ev["gates"] = [
            {"name": g["metadata"]["name"], "phase": (g.get("status") or {}).get("phase")}
            for g in (json.loads(gates.stdout).get("items") or [])
        ]
    (out_dir / "workflow-evidence.json").write_text(json.dumps(ev, indent=2, default=str))
    return ev


# ---- Report writer ---------------------------------------------------------

def write_report(out_dir: Path, plan: dict, wf_name: str,
                 phase: str, elapsed: float, evidence: dict, parity: dict | None) -> None:
    md = [f"# Migration Companion — Empirical Report",
          "",
          f"Plan: **{plan.get('name', 'unnamed')}**   "
          f"Generated: {time.strftime('%Y-%m-%d %H:%M:%S %Z')}",
          "",
          f"- Workflow: `{wf_name}`",
          f"- Final phase: **{phase}**",
          f"- Elapsed: {elapsed:.1f}s",
          f"- Source: {plan['source']['engine']} {plan['source'].get('version', '?')} "
          f"-> {plan['source'].get('endpoint') or '(BYOS)'}",
          f"- Target: {plan['target']['engine']} {plan['target'].get('version', '?')} "
          f"-> {plan['target']['endpoint']}",
          ""]
    if evidence.get("failed_nodes"):
        md += ["## Failed steps", ""]
        for n in evidence["failed_nodes"]:
            md += [f"- **{n['name']}** ({n['type']}): {n.get('message') or ''}"]
        md += [""]
    if evidence.get("gates"):
        md += ["## Approval gates", ""]
        for g in evidence["gates"]:
            md += [f"- {g['name']}: {g['phase']}"]
        md += [""]
    if parity:
        s = parity["summary"]
        md += ["## Parity", "",
               f"- Match: **{s['match']}**   Divergent: **{s['divergent']}**   Missing: **{s['missing']}**",
               ""]
        md += ["| Index | Source count | Target count | Verdict |",
               "|-------|--------------|--------------|---------|"]
        for name, i in parity["indices"].items():
            md += [f"| `{name}` | {i.get('source_count')} | {i.get('target_count')} | {i.get('verdict')} |"]
        md += [""]
        divergent = [i for i in parity["indices"].values() if i["verdict"] == "DIVERGENT"]
        if divergent:
            md += ["### Divergences", ""]
            for i in divergent:
                md += [f"- `{i['name']}`:",
                       f"  - unexpected settings drift: {i.get('settings_diff_unexpected') or []}",
                       f"  - mapping drift: {i.get('mapping_diff') or []}"]
            md += [""]

    md += ["## Artifacts", "",
           f"- `{out_dir}/workflow.yaml` — the submitted Argo Workflow",
           f"- `{out_dir}/workflow-evidence.json` — phase/node/gate tree",
           f"- `{out_dir}/parity.json` — per-index diff JSON",
           f"- `{out_dir}/watch.log` — live phase/approval log",
           ""]

    (out_dir / "report.md").write_text("\n".join(md))


# ---- Main ------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--kubeconfig", default=None)
    ap.add_argument("--namespace", default=None)
    ap.add_argument("--timeout", type=int, default=1200)
    ap.add_argument("--source-user", default=None, help="Creds for parity check (host-side)")
    ap.add_argument("--source-password", default=None)
    ap.add_argument("--target-user", default=None)
    ap.add_argument("--target-password", default=None)
    ap.add_argument("--source-endpoint-override", default=None,
                    help="Host-reachable endpoint for parity (e.g. https://localhost:19200 via port-forward). "
                         "Defaults to plan.source.endpoint.")
    ap.add_argument("--target-endpoint-override", default=None)
    ap.add_argument("--ensure-secrets", action="store_true",
                    help="If set, create k8s secrets for src/tgt basic auth using --source-user etc.")
    ap.add_argument("--skip-workflow", action="store_true",
                    help="Emit workflow.yaml + run parity only (no submit). Useful for debugging.")
    args = ap.parse_args()

    plan = json.loads(args.plan.read_text())
    errs = validate(plan)
    if errs:
        for e in errs:
            print(f"✗ {e}", file=sys.stderr)
        return 1

    kubeconfig = args.kubeconfig or (plan.get("empirical") or {}).get("kubeconfig")
    if not kubeconfig:
        print("✗ kubeconfig required (via --kubeconfig or plan.empirical.kubeconfig)", file=sys.stderr)
        return 2
    ns = args.namespace or (plan.get("empirical") or {}).get("namespace", "ma")
    args.out.mkdir(parents=True, exist_ok=True)

    # 1. Emit workflow
    wf_path = args.out / "workflow.yaml"
    wf_doc = render_workflow(plan, namespace=ns)
    wf_path.write_text(to_yaml(wf_doc))
    print(f"✓ wrote {wf_path}")

    # 2. Ensure secrets
    if args.ensure_secrets:
        src_auth = (plan.get("source") or {}).get("auth") or {}
        if src_auth.get("mode") == "basic" and src_auth.get("secretName") and args.source_user:
            ensure_secret(kubeconfig, ns, src_auth["secretName"], args.source_user, args.source_password or "")
        tgt_auth = (plan.get("target") or {}).get("auth") or {}
        if tgt_auth.get("mode") == "basic" and tgt_auth.get("secretName") and args.target_user:
            ensure_secret(kubeconfig, ns, tgt_auth["secretName"], args.target_user, args.target_password or "")

    evidence: dict[str, Any] = {}
    wf_name = ""
    phase = "Skipped"
    started = time.monotonic()

    if not args.skip_workflow:
        # 2.5 Preflight: clear stale MA CRs from prior failed runs (testsnapshot collision trap).
        preflight_cleanup(kubeconfig, ns)

        # 3. Submit
        wf_name = submit_workflow(kubeconfig, wf_path)

        # 4. Watch (and auto-approve gates if configured)
        auto = bool((plan.get("gates") or {}).get("autoApprove", False))
        phase = watch_workflow(kubeconfig, ns, wf_name, auto, args.timeout,
                               args.out / "watch.log")
        print(f"  = workflow finished phase={phase}")

        # 5. Collect evidence
        evidence = collect_workflow_evidence(kubeconfig, ns, wf_name, args.out)

    elapsed = time.monotonic() - started

    # 6. Parity
    parity = None
    src_ep = args.source_endpoint_override or (plan.get("source") or {}).get("endpoint")
    tgt_ep = args.target_endpoint_override or (plan.get("target") or {}).get("endpoint")
    if src_ep and tgt_ep and args.source_user and args.target_user:
        try:
            # Enumerate source indices
            cat = _http(f"{src_ep}/_cat/indices?format=json", args.source_user, args.source_password,
                        True)
            if cat.get("ok"):
                names = [r["index"] for r in cat["json"] if not _is_system_index(r["index"])]
                parity = diff_indices(src_ep, tgt_ep,
                                      (args.source_user, args.source_password),
                                      (args.target_user, args.target_password),
                                      True, True, names)
                (args.out / "parity.json").write_text(json.dumps(parity, indent=2))
                print(f"✓ parity -> {args.out / 'parity.json'}")
        except Exception as e:
            print(f"! parity skipped: {e}", file=sys.stderr)

    # 7. Report
    findings = {
        "plan": plan, "workflow_name": wf_name, "phase": phase,
        "elapsed_seconds": elapsed, "evidence": evidence, "parity": parity,
    }
    (args.out / "findings.json").write_text(json.dumps(findings, indent=2, default=str))
    write_report(args.out, plan, wf_name, phase, elapsed, evidence, parity)
    print(f"✓ report -> {args.out / 'report.md'}")

    return 0 if phase in ("Succeeded", "Skipped") else 3


if __name__ == "__main__":
    sys.exit(main())
