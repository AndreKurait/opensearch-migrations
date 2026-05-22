# Architecture notes for humans + AI contributors

This file describes the layering, model-composition strategy, and any
deviations from `PLAN.MD`. Keep it short — 1–2 pages — and update it
whenever a structural decision changes.

## Layering at a glance

```
cmd/tui/main.go                 ← entrypoint; lifecycle owner
   │
internal/app/app.go             ← composition root; owns broker + service goroutines
   │
internal/feature/*              ← domain services (AWS, helm, agents, artifacts, deploy)
   │
internal/ui/                    ← TUI surface — STRICTLY no I/O
   │
   ├── ui.go                    ← root model; page state machine
   ├── pages/<page>             ← one Model per page
   ├── components/              ← reusable widgets (TBD; v1 inlines via lipgloss)
   ├── styles/                  ← single source of style truth
   ├── keys/                    ← single source of bindings + help text
   ├── msg/                     ← cross-cutting tea.Msgs
   ├── dialog/                  ← stack-based modal overlay
   └── workspace/               ← façade that the UI consumes
```

## Hard rules

1. **internal/ui does no I/O.** No DB, no HTTP, no `os.ReadFile`. Async
   work flows in via `pubsub.Broker[tea.Msg]` as `tea.Msg`s. Enforced by
   `depguard` rule `tui-no-io` in `.golangci.yml`.
2. **No `fmt.Print*` in TUI mode.** Anything that writes to stdout/stderr
   corrupts the alt-screen. Enforced by `forbidigo`.
3. **Lipgloss v2 only.** `charm.land/...` paths everywhere; mixing with
   v1 explodes at runtime. Enforced by `depguard` rule `no-charm-v1`.
4. **Use `lipgloss.Width(s)`, never `len(s)` for layout math.** No
   automated lint catches this — code review responsibility.
5. **Publishers select on `ctx.Done()`.** See `internal/pubsub/broker.go`
   doc and `internal/app/app.go` Shutdown for the §9.2 contract.

## Model composition

We follow the conventional Bubble Tea pattern: every page is its own
`tea.Model`. The root forwards messages to the active page via
`dispatchPage` / `dispatchKey`. This maximizes per-unit testability —
each `Update` is table-driven.

The escape hatch (Crush's "imperative sub-component" pattern, where the
root is the only `tea.Model` and children are plain structs) is
**deliberately not adopted in v1**. Revisit only if profiling shows
message propagation is the bottleneck.

## Strict version pinning

The TUI knows exactly one MA version per release (PLAN locked **O**).
v1.0 verifies against MA 3.2.1 only. Any other version is hard-refused
on launch. The pinning logic lives in `internal/feature/artifacts/` and
the upstream-sync CI guard is documented in PLAN §0.1.

## v1 simplifications (with TODO links)

- **Skill bundle is reverse-translated at install time** from
  `kiro-assistant.tar.gz`, not pre-built upstream (UX.md §0.7).
  → `internal/skillkit/` + UX.md TODO row.
- **Helm install is a subprocess to `helm`**, not `helm.sh/helm/v3`.
  Switching to the Go SDK is straightforward; we just don't pay the
  k8s.io transitive dep cost yet. → `internal/feature/deploy/deploy.go`
  package doc.
- **Kiro online-version check is skipped** (PLAN locked **r10**) — no
  clean public version endpoint at kiro.dev.
- **Manual deploy view ticks** are simulated until the AWS deploy
  driver streams real CFN events into the broker. → `internal/ui/pages/deploy/deploy.go`.

## Testing strategy

- **Unit (Update + cmd factories):** the bulk; table-driven; fast.
  Goal coverage ≥ 85% on `internal/ui/pages/**`.
- **Golden snapshots:** ONE per page-level major state (landing /
  loaded / error). Total `testdata/` budget = 1MB enforced by
  `make testdata-budget`.
- **e2e (`teatest/v2`):** 5–10 flows max; we have one launch-and-quit
  smoke test in `internal/ui/golden_test.go`. Add only after a
  regression demands one.
- **Race tests:** `internal/pubsub/broker_test.go` runs with `-race`
  per PLAN §9.6.

## Deliberately out of scope for v1

- Configurable key bindings (PLAN §8.3 stub kept; `keys.KeyMap.Apply` not yet wired).
- Telemetry — locked **L** says no.
- Multi-region session switching within a single TUI launch (UX.md §17 J).
- Update flow MA → MA+1 (UX.md §13.2 G; defer to v2).

## Cross-platform notes

- macOS: `t.TempDir()` returns `/var/...`, which is on the workdir
  deny-list — tests under `internal/workdir/` use `safeTempDir(t)`
  instead. See `workdir_test.go`.
- Windows: untested in v1. The lifecycle and styling are platform-clean,
  but the `syscall.Exec` post-handoff is POSIX-only — Windows will need
  `os.Exec` + Wait equivalent.
