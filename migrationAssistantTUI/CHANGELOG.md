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
- Verified: 13 packages, 74 test cases, 54% line coverage, all green
  under `-race` in <22s wall-clock.
