# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial scaffold per `PLAN.MD` (Bubble Tea v2, Lipgloss v2, Bubbles v2).
- Pages: welcome, intent, wizard (10 steps), review, deploy, handoff.
- Domain services: AWS (sts/ec2/cloudformation), agent detector,
  artifact source, CFN+helm deploy driver.
- Workspace guards (UX.md §0.4), state file, HANDOFF.md writer,
  skill-kit adapter for Kiro and Claude Code.
- Pubsub broker with race-tested lifecycle (PLAN §9).
- Themes: Dark (default), Light, HighContrast.
- Single golden snapshot for the welcome page (PLAN §11.3).
- Lint policy: `forbidigo` ban on `fmt.Print*` in `internal/ui/`,
  `depguard` ban on charm v1 imports.
- E2E test driver (`internal/ui/e2e_test.go`) running the full state
  machine welcome → intent → wizard → review → deploy → handoff via
  direct `Update()` dispatch with timeout-bounded cmd execution.
- Help overlay (`?` toggles) lists every binding bound to the active page.
- Status bar honors `StatusMsg.TTL` so transient hints retract
  themselves; previously they stuck forever.
- Goodbye/handoff banner is now printed AFTER the alt-screen tears
  down, so users can scroll back to the kubectl/agent command.
- Workspace state machine flips to `in_progress` when the user clicks
  Launch, `failed` if the deploy reports a fatal phase event.

### Fixed
- Stage edits in the wizard now propagate into `StackName` (so
  `dev → MA-Dev`, `prod → MA-Prod`); previously the StackName was
  pinned to the default and CFN exports collided across stages.
- Wizard subnet/VPC pickers use `key.Matches` against `Wizard.Toggle`
  / `.Up` / `.Down` (was raw `k.String()` checks that missed
  KeySpace-as-named).
- Review screen blocks `[enter]` when the chosen stage already has an
  MA install in the chosen region. Previously the conflict banner
  appeared but `LaunchMsg` still fired, burning ~12 minutes on a
  doomed deploy.
- Review's `aws-bootstrap.sh` argv now emits one
  `--eks-access-principal-arn` flag per `AdditionalAccessARNs` entry.
- Wizard EKS-access step replaces (rather than appends) the principal
  list when revisited.
- `dispatchKey` and `dispatchPage` consolidated — dropping the dual
  switch; welcome's mode capture happens uniformly.
- Stage parser (`StagesFromExports`) lifted to `feature` so welcome
  and root share one implementation.
- Auto-route to the installed-action picker only happens once per
  session and offers an `[esc]/[b]` back-to-welcome escape.
- Default helm namespace is `ma` (matches `aws-bootstrap.sh`),
  previously `migration-assistant`.
- Skill-kit installs the Claude Code adapter when Claude is the
  detected agent, not always the Kiro layout.
- Handoff banner renders the actual detected agent's command.
- Welcome resume hint promised `[c]` and `[d]` keys; only `[c]` is now
  wired (clears the hint), and `[d]` was removed.

### Removed
- Dead `tickAdvance` legacy simulation path in the deploy page.
- Dead `resolveS3RouteTables` helper (unused; restore when wiring
  `S3EndpointRouteTableIds`).
- Dead empty switch in `versioncheck.Compare`.
- Test-artifact directories accidentally committed at repo root.
- Dead `tickAdvance` test (`TestE2E_DeployTickAdvances` was just
  `t.Skip`).
- Dead `SwitchProfileMsg.ProfileName` field (never set).
- Dead `dispatchKey` (consolidated into `dispatchPage`).

### Round 16-32 additions

- `[s]` save & exit on wizard non-textinput steps writes
  `.ma-session.json` to the workdir; the next launch auto-restores.
- `[/]` filter on VPC/subnet picker steps; substring match across
  ID/Name/CIDR/AZ/RouteLabel.
- Page-aware `?` help overlay via `keys.PageHelp`.
- CLI flags via Go's `flag` package: `-v/--version`, `-h/--help`,
  `--debug` (verbose logging).
- Welcome `[enter]` on existing-install routes:
  `installed` → handoff, `failed/in_progress` → review, else intent.
- Welcome shows a yellow "no agents installed" hint when none detected.
- Handoff page warns if `AgentBin == ""` in agent mode.
- Installed-action `Uninstall` now opens a confirm dialog and prints
  the actual `helm uninstall` + `aws cloudformation delete-stack`
  commands in the goodbye banner.
- Deploy page: title flips to "Deploy complete" / "Deploy failed",
  spinner stops once the run is over, all-phases-done is robust to
  future phase additions (no hardcoded last-index check).
- Deploy `[esc]` (background) emits `BackgroundedMsg` so root
  navigates back to welcome with a status hint.
- Intent `[esc]` returns to welcome; `[k]` was the skip hotkey but
  consumed the textarea — moved to `[ctrl+k]`.
- Deploy stress-tests under `-race` for 64 subscribers / 1000
  publishes.
- Tests added for: dialog stack `Push`/`Replace` semantics, dialog
  `replace-with-nil` panic safety, `keys.PageHelp` / `FormatHelp`,
  `installed` page actions, `marelease.Fetch` against an httptest
  server (URL exposed as a var), `config` load/save round-trip,
  `styles.By Name`, `log.Panic` shape, `launch.SaveWizardSession`
  round-trip + corruption.

### Cosmetic / structural

- `runHandoff` extracted from `main.go` for clarity + testability.
- Saved-session restore is wired through a `sessionLoadedMsg` so root
  Update owns the only mutation of `m.workdirPath` (no goroutine
  data race).
- `feature.StagesFromExports` lifted to the domain package; root's
  ad-hoc copy and welcome's parser both delegate.
- `Workspace` interface gains `SetWorkdir`/`SetAgentBin`/`SetGoodbye`
  so tests don't need ad-hoc type assertions.
