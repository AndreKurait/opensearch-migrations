# GAPS.md — aws-bootstrap.sh → migrationAssistantTUI absorption inventory

This file is the canonical "what's left between the shell script and the TUI" ledger.
Goal: make the TUI the **sole** authoritative entrypoint for installing, bootstrapping,
deploying, and entering Migration Assistant on EKS, retiring
`deployment/k8s/aws/aws-bootstrap.sh` (1264 lines) and `bootstrap-kiro-agent.sh`
(122 lines).

Sections track each capability of the script, where it lives in the script, whether
the TUI's `internal/feature/deploy` package implements it today, and the work to
absorb it. A capability marked **DROP** is intentionally not ported (legacy /
optional flags whose use cases are covered by simpler primitives).

Surfaces not yet in deploy package are tracked as concrete tasks with target file
paths under `internal/feature/...` and `internal/ui/pages/...`.

---

## 1. CLI flag parity

Script flags (canonicalised — see `aws-bootstrap.sh:75-255`) vs TUI `Params`
(`internal/feature/deploy/deploy.go`).

Status legend:  ✅ done · 🟡 partial · 🔴 missing · ⛔ DROP (intentional).

| script flag | TUI Params field | status | absorption notes |
|---|---|---|---|
| `--region` | `Region` | ✅ | wizard prefill from `~/.aws/config` |
| `--stack-name` | `StackName` | ✅ | TUI defaults to `migration-assistant-<stage>` |
| `--stage` | `Stage` | ✅ | wizard text input, default `dev` |
| `--deploy-create-vpc-cfn` | `Scope=create` | ✅ |  |
| `--deploy-import-vpc-cfn` | `Scope=import` | ✅ |  |
| `--skip-cfn-deploy` | `Scope=skip-cfn` | ✅ |  |
| `--vpc-id` | `VPCID` | ✅ |  |
| `--subnet-ids` | `SubnetIDs` | ✅ | comma-string split |
| `--vpc-endpoints` | `VPCEndpoints` | ✅ |  |
| `--ma-version` | `MAVersion` | ✅ | pin defaults to `3.2.1` (UX.O) |
| `--tls-mode` (`pca-existing`/`pca-create`/`self-signed`) | `TLSMode` | ✅ |  |
| `--pca-arn` | `PCAARN` | ✅ |  |
| `--namespace` | `Namespace` (default `ma`) | ✅ |  |
| `--release-name` | `ReleaseName` | ✅ |  |
| `--eks-access-principal-arn` | `EKSAccessARNs` (slice) | ✅ | TUI absorbs caller ARN automatically |
| `--build` | (deploy.Params.Build — pending) | 🔴 | T-1: add `Build bool` + gradle `:buildImages:buildImagesToRegistry` invocation. **Defaults FALSE** in TUI; opt-in via wizard "build images from source?" |
| `--push-images-to-ecr` | (pending) | 🔴 | T-2: ECR mirroring (see §3) |
| `--ma-images-source` | (pending) | 🔴 | T-2 |
| `--use-public-images` | (pending — controls helm `images.*` keys) | 🔴 | T-2 |
| `--ecr-pull-through-endpoint` | (pending) | 🔴 | T-2 (advanced; gate behind expert pane) |
| `--use-general-node-pool` | (pending) | 🔴 | T-3: helm `--set cluster.useCustomKarpenterNodePool=false` |
| `--disable-general-purpose-pool` | (pending) | 🔴 | T-3 |
| `--ignore-checks` | (pending) | 🟡 | wizard always runs preflight; flag is a CI escape hatch — add `Params.IgnorePreflight bool` |
| `--skip-test-images` | (pending) | 🔴 | T-2 (build-only) |
| `--skip-setting-k8s-context` | (pending) | 🟡 | TUI always sets context; add expert toggle |
| `--skip-console-exec` | n/a | ⛔ | TUI's whole point is the post-deploy console handoff (UX.r5: `syscall.Exec` → kubectl exec) |
| `--kubectl-context` | (pending) | 🟡 | TUI derives from cluster name; add override |
| `--extra-helm-values` | (pending) | 🔴 | T-4: `Params.ExtraValuesFiles []string` + glue files in run dir |
| `--base-dir` | n/a | ⛔ | TUI runs from its own workdir (`./opensearch-migration-<account>-<region>/`) |
| `--image-tag` | (pending) | 🔴 | T-2: default `latest`, override per UX.O git-SHA pinning |
| `--ecr-registry` (env `MIGRATIONS_ECR_REGISTRY`) | derived from `CFNExports` | ✅ | exports parser already covers it |

Net: 7 flags missing (T-1..T-4), 3 partial, 2 DROP. All missing flags fold into
**three** new `Params` fields and **one** new feature subpackage (`feature/imageops`).

---

## 2. Capability map (what the script DOES, in order)

| script line | capability | TUI location | status | gap → action |
|---|---|---|---|---|
| 252 `validate_args` | argparse + mutually-exclusive scopes | `internal/ui/pages/wizard` (validation lives in form layer) | ✅ |  |
| 386 subnet check | DescribeSubnets preflight | `feature/deploy.preflightSubnets` (pending) | 🔴 | T-5: port `aws ec2 describe-subnets` reachability check; emit warnings via `PhaseEvent` |
| 426 endpoint expand | aliases (`s3`, `ecr`, `ecr-api`, `kafka`) → service names | (pending) | 🟡 | T-5: small `vpcEndpointAliasMap` in deploy.go |
| 431 version resolve | `RELEASE_VERSION=$(curl …/latest)` else `--ma-version` | `feature/deploy.resolveMAVersion` | ✅ | already pinned to 3.2.1 (UX.O); add fall-through to GitHub `releases/latest` for opt-out |
| 446 TOOLS_ARCH | infer `linux_arm64`/`linux_amd64` | `feature/tools.archDetect` | ✅ |  |
| 459 HELM_VERSION=3.14.0 | helm install | `feature/tools.HelmInstaller` | ✅ | T-3b: lift to const `HelmVersion = "3.14.0"` (currently hardcoded inline) |
| 463 `install_helm` | `curl get-helm-3 \| bash` (or pinned URL) | `feature/tools.HelmInstaller.Install` | ✅ | already absorbed via `ToolInstaller` interface |
| 478 `get_cfn_export` | `aws cloudformation list-exports` | `feature/deploy.fetchExports` | ✅ |  |
| 515 `check_existing_ma_release` | `helm status` precondition | `feature/deploy.checkExistingRelease` (pending) | 🔴 | T-6: pre-helm gate; `helm status <release> -n <ns>` exit 0 → refuse install with actionable message ("run `helm uninstall` first") |
| 552 CFN deploy | `aws cloudformation deploy` | `feature/deploy.runCFN` | ✅ | covered by SDK call |
| 691 export shell vars | parse `MigrationsExportString-<stage>-<region>` | `feature/deploy.parseExports` | ✅ |  |
| 738 `aws eks update-kubeconfig` | kubeconfig wiring | `feature/deploy.writeKubeconfig` | ✅ | uses native AWS SDK STS+token writer |
| 749 EKS access entry | DescribeAccessEntry / Create / AssociateAccessPolicy | `feature/deploy.ensureAccessEntries` | ✅ |  |
| 773 nodepool re-enable | UpdateClusterConfig | (pending) | 🔴 | T-3: edge case for `--disable-general-purpose-pool` + reboot; wizard hides behind "advanced" |
| 794 subnet preflight (NAT/IGW) | `aws ec2 describe-route-tables` | (pending) | 🔴 | T-5 |
| 897 `_bootstrap_source_helpers` | `source` `mirror_*.sh`, `chartOps.sh` | (pending) | 🔴 | T-2: rewrite as `internal/feature/imageops` Go package wrapping `crane`. The shell helpers are already self-contained — port verbatim semantics, not the bash mechanics |
| 915 mirror images | `crane copy` × N | T-2 |  |  |
| 1047 image source | `--set images.<svc>.tag=…` | `feature/deploy.imageFlags` (partial) | 🟡 | T-2: distinguish public-ECR vs private-ECR vs locally-built |
| 1080 chart source | download chart tgz from GitHub release | `feature/deploy.fetchChart` | ✅ | already implemented |
| 1092 helm install | `helm install …` | `feature/deploy.runHelm` | ✅ | absorbed |
| 1106 TLS_HELM_FLAGS | per `tls_mode` flag building | `feature/deploy.runHelm` switch on `TLSMode` | ✅ |  |
| 1109 NODEPOOL_HELM_FLAGS | `--set cluster.useCustomKarpenterNodePool=false` | (pending) | 🔴 | T-3 |
| 1115 PCA Pod Identity Association | `aws eks list/describe/create-pod-identity-association` | (pending) | 🔴 | T-6: when `TLSMode=pca-*`, ensure PIA for SA `aws-pca-issuer`/`ack-acmpca-controller` |
| 1216 EXTRA_VALUES_FLAGS | comma-split `-f` files | (pending) | 🔴 | T-4 |
| 1242 `kubectl config set-context … --namespace=` | post-install ns pin | `feature/deploy.pinNamespace` | ✅ |  |
| 1244 disable general-purpose post-install | UpdateClusterConfig | (pending) | 🔴 | T-3 |
| 1259 console exec handoff | `kubectl exec --stdin --tty migration-console-0` | `internal/ui/pages/handoff` (uses `syscall.Exec`) | ✅ | UX.r5; superior to `--skip-console-exec` since the TUI exits cleanly leaving the user inside the pod |

---

## 3. New feature packages required

### T-2 — `internal/feature/imageops` (NEW)

Replaces `mirror_images_to_ecr`, `mirror_charts_to_ecr`, `generate_private_ecr_values`,
the `MA_IMAGES` block, and the `crane_copy_retry` retry loop. Public surface:

```go
type Mirror interface {
    MirrorPublicToPrivate(ctx, src PublicImageList, dst ECRTarget, opts) error
    MirrorMAImages(ctx, version, dst ECRTarget) error
    GeneratePrivateECRValuesFile(dst ECRTarget) (path string, err error)
}
```

Subprocess to `crane` (already a runtime dep of the script — avoid pulling
go-containerregistry directly; +20 MB binary). Retry policy: 5 attempts,
exponential backoff (5/10/20/40/80s) — same as script.

### T-3 — `internal/feature/nodepool` (NEW, small)

Wraps `aws eks describe-cluster` + `update-cluster-config` for the
general-purpose-nodepool dance. Two methods: `EnsureEnabled(ctx)` and
`Disable(ctx)`. Both block on `aws eks wait cluster-active`.

### T-5 — preflight in `internal/feature/preflight` (NEW)

- subnet reachability (NAT / IGW / VPC-endpoint coverage)
- VPC-endpoint alias resolution
- helm-release-already-exists check (T-6 lives here too)

Wired into wizard via a "Preflight" page that appears between "Configure" and
"Deploy"; `IgnorePreflight=true` skips it.

### T-6 — Pod Identity Associations for PCA / ACK

Already partially in `feature/deploy`; needs:
- `ensurePIA(ctx, namespace, sa, roleARN)` helper
- triggered by `TLSMode=pca-existing` (SA: `aws-pca-issuer`)
- triggered by `TLSMode=pca-create` (SAs: `aws-pca-issuer` + `ack-acmpca-controller`)

---

## 4. UX surfaces that need wiring

The TUI today has wizard pages for "Welcome" and (stub) "Deploy". The full
absorbed flow needs these pages, in order:

1. **Welcome** (✅ exists) — tool detection (helm, kubectl, aws) + install button
2. **Identity** (🔴) — `aws sts get-caller-identity` confirmation; offer profile picker
3. **Configure** (🟡) — region, stage, stack name, scope, VPC mode (covered by `Params`)
4. **TLS / Auth** (🔴) — TLS mode + PCA ARN form; conditional fields
5. **Image Source** (🔴) — `public-ECR` (default) / `private-ECR-mirror` (T-2) / `build-from-source` (T-1)
6. **Preflight** (🔴) — subnet + helm-release checks (T-5/T-6)
7. **Deploy** (🟡) — phased progress: cfn → exports → kubeconfig → access-entry → mirror → helm → wait
8. **Handoff** (✅) — `syscall.Exec` to `kubectl exec -it migration-console-0 -- /bin/bash`

Pages 2/4/5/6 are net-new; 3 and 7 need expansion.

---

## 5. bootstrap-kiro-agent.sh absorption

`bootstrap-kiro-agent.sh` (122 lines) does **two** things:

1. Install `helm` + `kiro-cli` via the same `ToolInstaller` pattern.
2. Run `kiro-cli chat --legacy-ui --agent opensearch-migration "@start"`.

Mapping into TUI:

- (1) → already done. `feature/tools.NewInstaller()` + `Supports()` returns
  `["helm", "kiro-cli"]`. Welcome page detects + installs.
- (2) → maps to `internal/feature/agents` "Hand off to Kiro" entry. UX.CC pins
  the agent list to `kiro-cli` + `claude`. After successful deploy, the Handoff
  page offers two paths:
    - **Pod handoff** (`kubectl exec` into `migration-console-0`) — UX.r5
    - **Agent handoff** (`kiro-cli chat --legacy-ui --agent opensearch-migration "@start"` or `claude`) — covers the bootstrap-kiro-agent.sh use case

Both use `syscall.Exec` so the TUI exits cleanly leaving the user inside the
chosen tool. **Net result**: bootstrap-kiro-agent.sh is fully absorbed by the
`(welcome.install) → (deploy) → (handoff.kiro)` flow.

---

## 6. End-state retirement plan

`deployment/k8s/aws/aws-bootstrap.sh` shrinks to a 30-line shim:

```bash
#!/usr/bin/env bash
# RETIRED — use the migrationAssistantTUI binary instead.
echo "aws-bootstrap.sh is retired. The Migration Assistant TUI replaces it." >&2
echo "  - Install:      curl -fsSL https://opensearch-migrations.io/tui/install.sh | bash"
echo "  - Run:          migration-assistant-tui"
echo "  - Old script:   git show <pre-tui-tag>:deployment/k8s/aws/aws-bootstrap.sh"
exit 2
```

`bootstrap-kiro-agent.sh` gets the same treatment.

---

## 7. Task ordering (next sessions)

1. **T-1** add `Params.Build`, gradle invocation, surfaced by Image-Source wizard page
2. **T-2** new `feature/imageops` package (crane subprocess) + Image-Source wizard page
3. **T-3** new `feature/nodepool` package + advanced toggle in wizard
4. **T-4** `Params.ExtraValuesFiles` + Configure wizard page row
5. **T-5** `feature/preflight` package + Preflight wizard page
6. **T-6** PIA helper inlined into `feature/deploy`
7. **Retire** the two scripts to shims; update `deployment/k8s/aws/README.md`
8. **CI** — `.github/workflows/migration-assistant-tui.yml` with `gradle build`
   + `gofmt -l . && go vet ./... && go test ./...`

After T-6 the TUI carries every capability of `aws-bootstrap.sh` minus the
hand-marked ⛔ DROP flags. After §6 the scripts physically disappear.
