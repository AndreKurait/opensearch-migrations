# Migration Assistant CLI (TUI)

A single-binary terminal UI that takes you from "I want to migrate to
OpenSearch" to a running migration console — either dropped into a
`migration-console-0` shell or handed off to an AI agent (Kiro / Claude
Code) primed with the migration skill kit.

> **Status:** v0.0.0-dev — implemented per `UX.md` v0.12 and `PLAN.MD`.
> v1 supports MA 3.2.1 only (strict pinning, locked **O**).

## Install

The TUI is now THE authoritative entry point for installing, bootstrapping,
deploying, and entering Migration Assistant on AWS EKS. It absorbs everything
that `deployment/k8s/aws/aws-bootstrap.sh` and `deployment/k8s/bootstrap-kiro-agent.sh`
used to do — see [`docs/GAPS.md`](docs/GAPS.md) for the absorption ledger.

**One-line install** (Linux / macOS, amd64 / arm64):

```sh
curl -fsSL https://opensearch-migrations.io/tui/install.sh | bash
migration-assistant
```

The installer downloads the latest pre-built binary from
[`opensearch-project/opensearch-migrations` releases](https://github.com/opensearch-project/opensearch-migrations/releases?q=tui-v),
verifies it against the published `checksums.txt`, and drops it into
`/usr/local/bin` (or `~/.local/bin` if the system path isn't writable).

**Pin a specific version**:

```sh
MA_TUI_VERSION=tui-v0.1.0 \
  curl -fsSL https://opensearch-migrations.io/tui/install.sh | bash
```

**Install from source**:

```sh
git clone https://github.com/opensearch-project/opensearch-migrations
cd opensearch-migrations/migrationAssistantTUI
make install                       # → /usr/local/bin/migration-assistant
make install PREFIX=$HOME/.local   # per-user
```

**Or via Gradle** (participates in the monorepo build):

```sh
./gradlew :migrationAssistantTUI:build
# binary at migrationAssistantTUI/build/bin/migration-assistant
```

## Quickstart

```sh
mkdir -p ~/migrations && cd ~/migrations
migration-assistant
```

The TUI:
1. Detects your AWS identity and any existing MA installs in this region.
2. Walks an intent-capture form so the chosen handoff target has context.
3. Walks the setup wizard — every option pre-filled with the most likely correct answer.
4. Shows the equivalent `aws-bootstrap.sh` invocation so you can verify before launch.
5. Streams CFN events + helm install in real time.
6. Hands off — `kubectl exec` into the console, OR `exec`s the agent
   CLI of your choice with the skill kit installed in `./.kiro/` or
   `./.claude/skills/opensearch-migration/`.

After handoff the TUI **exits** (locked **r5**). The agent or `kubectl
exec` is the only thing alive in your terminal.

## Workspace layout

Each install creates a named subdirectory under your launch cwd:

```
./opensearch-migration-<account>-<region>/
├── HANDOFF.md
├── .ma-state.json
├── .kiro/                 # if Kiro is the chosen agent
├── .claude/skills/...     # if Claude Code is the chosen agent
├── transcripts/           # yours (TUI never writes here)
├── reports/               # yours
└── notes/                 # yours
```

The TUI refuses to operate from `/`, exact `$HOME`, `/tmp`, `/var/*`,
`/usr/*`, `/opt/*`, `/etc/*`, or any non-writable cwd
(UX.md §0.4 guard 1).

## Key bindings (working set)

| Key       | Action                                                   |
|-----------|----------------------------------------------------------|
| `enter`   | Confirm current step / launch / open console             |
| `↑↓`      | Navigate within a list                                   |
| `space`   | Toggle in multi-select (subnets, VPC endpoints)          |
| `/`       | Filter the current list                                  |
| `esc`/`b` | Back one step / dismiss modal / background a deploy      |
| `s`       | Save session and quit                                    |
| `a`       | Switch AWS account / profile                             |
| `?`       | Help overlay                                             |
| `q`       | Quit                                                     |
| `^c`      | Same as `q`                                              |

## Development

```sh
make test            # go test -race ./...
make test-update     # regenerate golden snapshots
make lint            # golangci-lint
make cover           # local coverage
make cover-xml       # Cobertura XML for Codecov
make testdata-budget # enforce 1MB testdata cap (PLAN §11.3)
```

Architecture rules and contributor notes: see `internal/ui/AGENTS.md`.

## Anti-goals (UX.md §18)

- Not a replacement for the migration console itself.
- Not an authoring tool for migration playbooks.
- Not a general AWS console replacement.
- Not opinionated about which agent CLI is "correct".
- Not a backup / data-recovery tool for the source cluster.
- No telemetry, ever (locked **L**).
