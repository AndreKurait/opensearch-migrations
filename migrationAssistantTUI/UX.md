# Migration Assistant CLI — UX Design (working draft)

> Status: **v0.12** — rounds 1–10 locked.
> Re-implements bootstrap.sh in Go, no scope cuts, strict version pinning.
> v1 supports Kiro + Claude Code; ships agent-agnostic skill kit;
> co-released from `opensearch-migrations` repo. Install / uninstall only.
> **The TUI's job is detect → install → exec into handoff target → exit.**
> It never stays alive after handoff. The agent CLI (or `kubectl exec` shell)
> owns everything after that. A pre-wizard "intent capture" step feeds
> context to both modes.

## Locked decisions

| #      | Decision                                                                  |
|--------|---------------------------------------------------------------------------|
| A      | Mode picker at welcome; switchable until final handoff                    |
| C      | Re-implement bootstrap.sh logic in Go (not subprocess) — see §0.1         |
| F      | AI-agent skill kit installed locally by default                           |
| K      | Go + Bubble Tea + Lipgloss, AWS SDK v2 for AWS calls                      |
| N      | No scope cuts — port full bootstrap.sh; emphasis on simpler UX            |
| O      | Strict version pinning of MA versions; refuse unverified versions         |
| I      | Resume reads both local lock file and AWS state                           |
| P      | Skill kit extracts to the cwd the TUI was launched from                   |
| D-r3   | v1 agent CLIs: Kiro + Claude Code. Q and Cline deferred                   |
| E-r3   | Ship agent-agnostic skill kit; adapt at runtime per agent — see §0.2      |
| M-r3   | TUI co-shipped from `opensearch-project/opensearch-migrations`            |
| G-r3   | v1 = install / uninstall only; warn when in-flight migration exists       |
| **r4** | **Resume shows side-by-side diff** of local-vs-AWS, user picks path       |
| **r4** | **Pre-wizard intent capture** (structured form) — see §3                  |
| **r5** | **TUI exits at handoff.** It does not run tools, drive the agent, or stay alive — see §0.3 |
| **r5** | Handoff = `exec`. Manual: `kubectl exec` to console. Agent: `exec` agent CLI in workdir |
| **R**  | **Named-subdir workdir** `./opensearch-migration-<account>-<region>/` + guards — see §0.4 |
| **S**  | **Skill bundle is a release artifact** in `opensearch-migrations`, version-pinned with MA |
| **Z**  | **`HANDOFF.md` with YAML frontmatter + markdown body**; `@start` skill reads it first |
| **L**  | **No telemetry.** Not opt-in, not crash reports. Out of scope for v1 and v2.       |
| **r6** | **Workdir is also the user's workspace** — transcripts/reports/notes live there — see §0.4 |
| **r6** | **Launch-time TUI update check**, with MA-reinstall warning — see §0.5            |
| **AA** | All updates treated the same — no security-vs-feature distinction in the prompt   |
| **CC** | **Never auto-clean** the workspace. User owns transcripts/reports/notes forever   |
| **r8** | **Agent CLI version check at launch** — flag outdated Claude/Kiro installs — see §0.6 |
| **r9** | **Artifact source order for v1:** GitHub release assets first; tag-pinned raw repo files as fallback (with TODO to promote upstream) — see §0.7 |
| **r10** | **Agent online-version check is optional.** If we can't find a clean source for an agent's latest version, we skip the comparison and show local version only — see §0.6 |

## 0.1 Implications of re-implementing bootstrap.sh in Go (full coverage)

The TUI re-implements all of `aws-bootstrap.sh` 3.2.1's behavior natively,
with no flag cuts — and a UX commitment that the TUI is *simpler* than
the script for users to drive. To make this sustainable, **we commit to:**

1. **Pin a supported upstream version** per TUI release. v1.0 verifies
   against MA 3.2.1 only. v1.1 verifies 3.2.1 + 3.2.2. The TUI hard-refuses
   versions it hasn't been verified against (locked **O**).
2. **Behavior parity test suite.** For each supported MA version, integ
   tests run a representative scenario through both the upstream script
   and the TUI in throwaway accounts; asserts on resulting CFN templates,
   helm release values, and EKS state.
3. **CI guard against upstream drift.** A scheduled job downloads the
   latest `aws-bootstrap.sh`, diffs against the verified version, and
   opens a ticket on any change so we can decide port-or-pin.
4. **The hardest features to port well — flagged for design attention:**
   - `--build` requires a local repo checkout and gradle. The TUI must
     detect / locate the checkout and surface gradle output sensibly.
     This is the longest-running, most error-prone path; it deserves its
     own progress sub-view.
   - `--ma-images-source` (cross-ECR mirror) is for airgapped clusters.
     Needs a "where are images coming from" picker.
   - `--tls-mode pca-create` creates a new AWS Private CA — irreversible
     (7-day delete delay). Must be a multi-step confirm.
   - `--helm-values <path>` lets advanced users override anything in the
     chart. We'll surface this as an "advanced overrides" file picker on
     the review screen, not a wizard step.
   - `--ignore-checks` opts out of safety checks. Available, but with a
     scarier confirm than the others.
   - `--disable-general-purpose-pool` is post-install cluster trimming.
     Lives in a "post-install tuning" panel after handoff, not the wizard.

### "Simpler from the user's perspective" — what this means concretely

We have feature parity, but not interface parity. Concrete simplifications:

- **No flag is mandatory in the wizard.** Sane defaults for everything
  a flag could specify; user accepts to advance.
- **One consolidated "advanced" step** at the end of the wizard exposes
  every rare flag in one screen, defaulted to off, behind a single "Show
  advanced options" toggle. Users who don't need them never see them.
- **Pre-flight all checks the script defers.** The script fails 8 minutes
  in if your subnets are isolated and you didn't pass `--create-vpc-endpoints`.
  The TUI catches this at subnet-pick time and offers the fix inline.
- **Detected → suggested.** Every list (VPCs, subnets, profiles) shows
  the AWS-resolved options ranked by "most likely correct" with the
  recommendation pre-selected. The user is never asked to know an ID.
- **Mistakes are reversible until launch.** Every wizard step is editable
  via `b` until the user hits the deploy button on the Review screen.

## 0.2 Skill kit format (consequence of locked E-r3)

We chose to ship an agent-agnostic skill bundle and adapt it at runtime
(rather than translate the upstream `.kiro/` per agent at install time).
That means **we own a skill kit format**. v1 of the format:

```
opensearch-migration-skills/
├── manifest.yaml              # name, version, MA-version-compat, entrypoint
├── skills/
│   ├── start.md               # entrypoint skill (matches upstream `@start`)
│   ├── snapshot.md
│   ├── metadata-migration.md
│   ├── backfill.md
│   ├── replay.md
│   ├── traffic-switch.md
│   └── …                      # one .md per migration phase
└── adapters/                  # ⚠ not files we author; written by TUI at runtime
    └── (agent-specific, generated)
```

At install time, the TUI:
1. Extracts the agent-agnostic bundle to `./<workdir>/opensearch-migration-skills/`.
2. Writes an adapter directory for the chosen agent:
   - **Kiro:** writes `./<workdir>/.kiro/` pointing at the bundle (or, if upstream
     ships its own `.kiro` already, prefer the upstream one for fidelity).
   - **Claude Code:** writes `./<workdir>/.claude/skills/opensearch-migration/`
     with each `.md` from the bundle plus a `SKILL.md` index.
3. Records the bundle version + agent + adapter version in the session file
   so we can detect drift / re-adapt on TUI updates.

**Authoring source of truth.** The OpenSearch project authors `.kiro/`
upstream. **In v1 (locked r9)**, the TUI generates the agent-agnostic
bundle *at install time* by reverse-translating `kiro-assistant.tar.gz`
from the matching MA release. This is a v1 simplification: the reverse
translation runs locally, not in our release pipeline, until upstream
ships an `opensearch-migration-skills.tar.gz` artifact (TODO; see §0.7).

For Kiro mode, we skip the reverse-translate step entirely and use
`kiro-assistant.tar.gz` directly — pixel-perfect with what the upstream
`bootstrap-kiro-agent.sh` produces.

---

## 0.3 The TUI exits at handoff (locked r5)

The TUI does three things — detect, install, hand off — then exits.
It does not stay alive. It does not run as a tool server. It does not
implement migration-execution tools.

**Manual handoff:**
```
exec kubectl --context=$CTX -n $NS exec -it migration-console-0 -- /bin/bash
```
The TUI's process is replaced by `kubectl exec`. When the user
`exit`s the pod shell, they're back at their original shell — not in
the TUI. To re-open the console, they re-run `migration-assistant`.

**Agent handoff:**
```
cd $WORKDIR && exec <agent-cli> [agent-specific flags]
```
The TUI:
1. Writes the agent-agnostic skill kit + per-agent adapter files into
   `$WORKDIR` (the launch cwd by default).
2. Writes a small "handoff brief" file (`./HANDOFF.md` or in-skill
   metadata) containing: AWS identity, region, EKS cluster name,
   wizard answers, source-cluster details, and the user's stated
   migration goal — so the agent has context without asking.
3. `cd`s into `$WORKDIR` and `exec`s the agent CLI.

After `exec`, the TUI is gone. Whatever the agent does — read the
skill kit, `kubectl exec` into the console itself, run commands, ask
the user for approval — is the agent's responsibility, using the
agent's own tool-calling model. **We do not provide tools. We do
not run a daemon. We do not see what happens next.**

### What this means for the rest of the design

- **No tool surface, no MCP server, no audit log of tool calls.**
  The agent's CLI may have its own logging; we don't.
- **No approval thresholds to maintain per console command.** The
  skill kit's prompts to the agent are where safety lives.
- **Approval prompts are the agent's job.** Skill markdown can say
  "always confirm before running traffic-switch"; whether the agent
  obeys is the agent's CLI's enforcement.
- **The handoff brief is the TUI's only job around context.** Once
  written, our work is done.
- **Resume after agent mode = re-run the TUI.** It detects "already
  installed", offers to re-open the agent (re-`exec`s with the same
  workdir), or switch to manual. Same for the manual user.

## 0.4 The workdir is the workspace (locked R + r6)

Every install creates one named subdirectory under the launch cwd:

```
./opensearch-migration-<account>-<region>/
├── HANDOFF.md                              # see §0.6
├── .ma-state.json                          # our marker file (account, region, MA ver, install timestamp)
├── .claude/skills/opensearch-migration/    # if Claude Code is the chosen agent
├── .kiro/                                  # if Kiro is the chosen agent
├── transcripts/                            # agent / console session transcripts
├── reports/                                # migration reports, audit logs
└── notes/                                  # user free-form notes
```

The TUI owns `HANDOFF.md`, `.ma-state.json`, and the `.<agent>/`
directory. Everything else (`transcripts/`, `reports/`, `notes/`) is
the user's. We create the directories on first install but never
modify their contents — they're for the user (or the agent, with the
user's permission) to write into.

### Guards (locked R)

1. **Refuse unsafe cwds.** If `pwd` resolves to `/`, exact `$HOME`,
   `/tmp`, `/var/*`, `/usr/*`, `/opt/*`, `/etc/*`, or any path the
   user can't write to: hard-fail with the rejection reason and
   suggest `mkdir -p ~/migrations && cd ~/migrations &&
   migration-assistant`. Do not silently fall back.
2. **Detect prior install** via `.ma-state.json` in the named subdir.
   - Same account + region + MA version → "Reuse this workspace?"
     prompt; defaults to yes.
   - Different account, region, or MA version → "Overwrite, rename
     existing, or pick a different cwd?" prompt; no default.
   - Marker exists but is corrupt → safe-mode: prompt user, never
     auto-clobber.
3. **Never write outside the named subdir.** The TUI's only outside
   write is the user-config file at
   `~/.config/opensearch-migration-assistant/`. Even "global" skill
   kits (if the user opts in) are a single symlink at
   `~/.<agent>/skills/opensearch-migration/` → workdir, not a
   duplicated tree.

### Workspace as a first-class artifact

Because users will accumulate transcripts and reports over the life of
a migration, the workdir is portable: the user can `tar` it, share it
with a teammate, or hand it to support. The marker file
(`.ma-state.json`) makes it self-describing — the TUI launched against
a copy of someone else's workdir refuses to operate (different account)
but offers to "open as read-only" for review.

## 0.5 Launch-time version check (locked r6)

Every launch, before doing anything else, the TUI checks for a newer
TUI release and prompts to update.

```
┌─ Update available ──────────────────────────────────────────────────┐
│                                                                      │
│  You're on TUI 1.0.0; latest is 1.1.0.                              │
│                                                                      │
│  ⚠ Updating the TUI also updates the Migration Assistant version    │
│    it deploys (1.0.0 → MA 3.2.1; 1.1.0 → MA 3.3.0).                 │
│    If you have an MA install in this workspace, you will need to    │
│    UNINSTALL and REINSTALL it before the new TUI will work.         │
│                                                                      │
│  ▸ Update now (downloads new binary; nothing changes in AWS)         │
│    Skip this check (use current TUI version)                         │
│    Always skip on this version (don't ask again until 1.2.0)         │
└──────────────────────────────────────────────────────────────────────┘
```

### Why the warning is mandatory

Strict version pinning (locked **O**) means TUI 1.1.0 only knows MA
3.3.0. A user who updates the TUI and tries to operate against an
existing MA 3.2.1 install will hit our version-refuse on the very
next "open console" or "uninstall" action. The warning surfaces this
*before* the user updates, so they can choose: stay on TUI 1.0.0 to
manage the existing install, or accept the uninstall/reinstall cost
to move forward.

### Mechanics

- **Check frequency:** once per launch, with a 24h local cache to
  avoid hammering GitHub on rapid relaunches.
- **Channel:** the latest release tag in
  `opensearch-project/opensearch-migrations` whose name matches our
  TUI artifact pattern.
- **Failure mode:** if GitHub is unreachable, log silently and
  continue — never block a launch on the version check.
- **Skip behavior:** "Skip this check" is per-launch. "Always skip on
  this version" pins the user to 1.0.0 until 1.2.0 ships, recorded in
  user config.
- **Update command:** the TUI shells out to its own update routine
  (`migration-assistant self-update` style); we don't auto-replace
  the binary mid-process.

### Interaction with workspace state

The version-check prompt knows whether an existing workspace is in
this cwd. If yes, the warning is bolder ("**existing install
detected**"). If no, the warning is a one-liner ("you'll deploy MA
3.3.0 next time you install"). Detected-no-install installs face zero
upgrade pain — the warning is purely informational.

## 0.6 Agent-CLI version check (locked r8)

In addition to the TUI-version check (§0.5), the welcome screen
detection (§7) reports installed agent-CLI versions alongside their
latest available versions. Outdated installs are flagged but never
forcibly updated — the user decides.

### What's shown on the welcome screen

```
AI agents
  claude-code   2.1.140    (latest 2.1.147 — 7 patch releases behind)
  kiro-cli      0.4.1      (online version unknown)
```

Outdated agents (where we have a remote version to compare against)
get a `⚠` annotation and a one-line action:

```
  ⚠ claude-code 2.1.140 is older than 2.1.147.
    [u] update now    [enter] continue with current
```

When we can't determine the latest version (e.g. Kiro), we show
local-version-only with no annotation. The check is best-effort, not
load-bearing.

### Version sources (locked r10 — best-effort)

| CLI         | Local version           | Latest version (remote)                                                        |
|-------------|--------------------------|--------------------------------------------------------------------------------|
| Claude Code | `claude --version`       | `GET https://registry.npmjs.org/@anthropic-ai/claude-code/latest` → `.version` |
| Kiro        | `kiro-cli --version`     | None — no clean public version endpoint at `kiro.dev`. Skipped (locked r10).   |

If Kiro upstream later publishes a version endpoint, we add it
without a doc-level decision.

### Comparison & semantics

- **Local version normalization:** strip leading `v`, ignore prerelease
  suffixes (`-beta.1`), parse as semver. If parse fails, treat as
  "unknown" and show the raw string with no comparison.
- **Behind-by classification:** patch difference → informational; minor
  difference → soft warning (`⚠`); major difference → hard warning,
  user must press `enter` explicitly to continue. *Reasoning: agent
  major bumps often change skill formats.*
- **Ahead of latest:** treat as fine ("local newer than latest known"),
  no warning. The user is on a beta or local build.

### Failure modes

- **Network unreachable:** show local versions only; no annotations.
  Log silently. Never block.
- **Registry returns garbage:** treat as network-unreachable; do not
  guess.
- **CLI not on PATH:** show "not installed" (existing behavior).

### Mechanics

- **Check frequency:** once per launch, with a 6h local cache (more
  aggressive than the TUI's own 24h, since agent CLIs ship faster).
- **Performed in parallel** with the TUI version check and AWS
  detection — never on the critical path of welcome-screen render.
- **No auto-update.** We surface the diff and the install command; we
  never run `npm install -g @anthropic-ai/claude-code` without the
  user pressing `[u]`.

### Why this matters even though we don't drive the agent

The skill kit format may track agent capabilities (e.g., a new
`@hook` syntax in a future Kiro release). A user on an older Kiro
running a newer skill kit will see the agent silently ignore parts of
the workflow. Surfacing the version drift on the welcome screen lets
the user catch it before the migration starts, not 20 minutes in when
a step doesn't fire.

## 0.7 Artifact sources for v1 (locked r9)

For every external artifact the TUI pulls, v1 follows this order:

1. **GitHub release asset** at the chosen MA tag.
2. **Raw repo file at the chosen tag** (e.g. `https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/3.2.1/path/to/file`).
3. **Hard-fail with a clear error** (no silent fallbacks past tag-pinned raw).

Every fallback to (2) is tagged with a **TODO** so we have a clean
backlog to promote upstream. Once an artifact is in the release, the
fallback path becomes legacy and we can remove it.

### Artifact catalog (verified against MA 3.2.1)

| Artifact                              | Source for v1                                                                              | TODO upstream                              |
|---------------------------------------|--------------------------------------------------------------------------------------------|--------------------------------------------|
| `aws-bootstrap.sh` (reference port)   | ✅ Release asset                                                                            | —                                          |
| Helm chart `migration-assistant-<ver>.tgz` | ✅ Release asset                                                                       | —                                          |
| CFN: Create-VPC EKS template          | ✅ Release asset (`Migration-Assistant-Infra-Create-VPC-eks.template.json`)                 | —                                          |
| CFN: Import-VPC EKS template          | ✅ Release asset (`Migration-Assistant-Infra-Import-VPC-eks.template.json`)                 | —                                          |
| Kiro skill bundle                     | ✅ Release asset (`kiro-assistant.tar.gz`)                                                  | —                                          |
| Kiro bootstrap script (reference)     | ✅ Release asset (`bootstrap-kiro-agent.sh`)                                                | —                                          |
| **Agent-agnostic skill bundle**       | ⚠ Not yet a release asset. v1 falls back to **deriving from `kiro-assistant.tar.gz`** at install time (§0.2 reverse-translation, run at install rather than at TUI release time). | **TODO:** ship `opensearch-migration-skills.tar.gz` as a release asset. |
| **Claude Code skill kit**             | ⚠ Not a release asset. v1 derives from the agent-agnostic bundle (or directly from `.kiro/`) at install time. | **TODO:** ship `claude-code-skills.tar.gz` as a release asset. |
| Kiro online-version check             | Skipped in v1 (locked **r10**) — no clean public source                                     | If Kiro publishes a version endpoint, opportunistically use it; no upstream change needed |
| **TUI binary**                        | ✅ Will be a release asset once we ship (locked **M-r3**)                                    | TUI 1.0.0 ships in same `opensearch-migrations` release as MA 3.2.x |
| Helm chart `valuesEks.yaml`           | Currently inside the `.tgz` chart (extracted at install time, matches upstream behavior)    | —                                          |

### Implications

- **The agent-agnostic skill bundle is generated at install time, not at release time, in v1.** The TUI extracts `kiro-assistant.tar.gz` and reverse-translates it on the fly. This contradicts what §0.2 implied (that we'd reverse-translate at our release time). The simpler v1 reality: install-time translation, with a TODO to move it back upstream.
- **The Claude Code skill kit is derived in-process** from the agent-agnostic bundle (or from `.kiro/` directly if we go that route). Same TODO.
- **One-off raw-repo pulls are gated to the tag-pinned URL.** Never fetch `main` or `latest` for content the user is going to deploy. If the file isn't at the tag URL, hard-fail with the missing path so the user can file an issue.
- **Every fallback to (2) is logged.** When the TUI uses a raw-repo URL, it prints a one-line note: `note: fetched <file> via raw repo (no release asset yet — see TODO).` This makes the upstream promotion backlog visible to users.

### Failure semantics

- Network unreachable → fail with the URLs we tried, in order.
- Release asset missing at the tag → fall back to raw repo (path TBD per artifact).
- Raw repo file missing at the tag → hard-fail with the GitHub issue link template pre-filled (`/issues/new?title=missing+release+asset+for+<artifact>+at+tag+<tag>`).
- Hash mismatch (if we publish hashes) → hard-fail; never auto-retry.

→ **Resolved by r10:** DD (Kiro version discovery) — skipped entirely; agent-CLI version comparison is best-effort.

## 1. North star

A single TUI that takes a user from "I want to migrate to OpenSearch" to one
of two end states:

- **Manual end state:** an interactive bash session inside the
  `migration-console-0` pod on the target EKS cluster.
- **AI-Agent end state:** a running AI-agent CLI primed with the
  `opensearch-migration` skill set, configured to drive the migration
  console on the user's behalf.

Both paths share the same *prerequisite*: the AWS infrastructure (CFN stack +
EKS + helm release) must exist. The TUI's primary job is making that
prerequisite painless, idempotent, and resumable.

The success metric is **"never makes the user re-think a decision they
already made."** State persists. Detection is progressive. Re-runs short-circuit.

---

## 2. The two-mode framing — challenged

You described two top-level modes: **Manual** and **AI Agent**. Read literally,
this is a false binary — the bootstrap script *is* automation, and "manual
mode" still means a TUI walking the user through it. The real distinction is
**what happens at the end**:

| Mode    | Setup phase (identical)         | Final handoff                          |
|---------|---------------------------------|----------------------------------------|
| Manual  | TUI walks through bootstrap.sh  | `kubectl exec` into `migration-console-0` |
| Agent   | TUI walks through bootstrap.sh  | Launches AI agent w/ migration skills  |

Two implications:

1. **Setup is shared.** The 80% of code paths (region picking, VPC detection,
   stack discovery, helm install) are identical between modes. Mode is a
   *handoff* choice, not a flow choice.
2. **Mode can be deferred.** Asking "Manual or AI?" up-front forces a
   premature commitment. We can ask it *only at the very end*, after the
   stack is healthy. Or: ask it up-front but let users switch at any time
   before the final handoff.

→ **Open Question A:** Mode at the start (your initial proposal) or mode at
the end? My recommendation: **ask at the start, but make it cheap to switch.**
Asking up-front lets us pre-flight agent-CLI detection in parallel with AWS
calls. Asking at the end better matches the "you've already done the work,
now what do you want?" mental model. Either works; we should pick.

---

## 3. Intent capture (pre-wizard, both modes)

Before any AWS work, the user fills a small structured form describing
the migration. This step is shown by default but can be skipped.

```
┌─ Tell us about your migration ───────────────────────────────────────┐
│                                                                      │
│  Source cluster                                                      │
│   Endpoint URL    [https://es.old-co.internal:9200             ]    │
│   Engine          [Elasticsearch 7.10  ▾                       ]    │
│   Auth            [Basic auth ▾    user [admin] password [•••] ]    │
│   Approx. size    [~2 TB / 800 indices                         ]    │
│                                                                      │
│  Target                                                              │
│   New OpenSearch domain (this tool can create one) ▸                 │
│   Existing OpenSearch domain                                         │
│   Self-managed OpenSearch                                            │
│                                                                      │
│  Migration goal (free text — used by AI agent if you choose Agent)   │
│   ┌──────────────────────────────────────────────────────────────┐ │
│   │ Migrate prod search index from on-prem ES 7.10 to OpenSearch │ │
│   │ 2.x with zero downtime. Replicate live writes during cutover.│ │
│   └──────────────────────────────────────────────────────────────┘ │
│                                                                      │
│   [enter] continue   [k] skip (fill in console later)                │
└──────────────────────────────────────────────────────────────────────┘
```

### Why this lives before the install wizard

- **Both modes benefit.** Manual users get this echoed in the console
  banner (`migration-console-0`) on entry. Agent users get it as
  initial context via `HANDOFF.md` (§12.4), eliminating the first 3-4
  turns of "what are we migrating?" Q&A.
- **Some answers feed install decisions.** Source-cluster network
  reachability shapes VPC subnet selection; planned size influences
  recommended node-pool sizes.
- **Skippable for resumes.** If a saved session already has intent,
  this step is hidden on resume.

### Validation

- URL: validated as parseable URL; ping-test deferred (we may not be
  network-attached yet).
- Auth credentials: stored *encrypted* in session file using the
  user's OS keychain (macOS Keychain, secret-service on Linux,
  Credential Manager on Windows).
- Free-text goal: no validation; sanitized before passing to agents.

→ **Open Question X (new):** Source-cluster credentials in the session
file are sensitive. Do we store at all (convenience for resume), or
prompt every launch? Recommendation: store in OS keychain, *not*
session file; reference by keychain item ID.

## 4. End-to-end flow (after intent capture)

```
┌──────────────────────────────────────────────────────────────────────┐
│   LAUNCH                                                             │
│                                                                      │
│   ┌──────────────────────────────────────┐                          │
│   │  Detect existing state (parallel):   │                          │
│   │   • saved sessions on disk           │                          │
│   │   • CFN stacks with MA exports       │                          │
│   │   • installed helm releases (if any) │                          │
│   │   • AWS identity / region            │                          │
│   │   • installed AI-agent CLIs on PATH  │                          │
│   │   • required tools (aws, kubectl, …) │                          │
│   └──────────────────────────────────────┘                          │
│                       │                                              │
│   ┌───────────────────┴────────────────────┐                        │
│   │            ROUTE BY STATE              │                        │
│   └───────────────────┬────────────────────┘                        │
│        ┌──────────────┼─────────────────┬────────────────┐          │
│        ▼              ▼                 ▼                ▼          │
│   Fresh start    Resume session   Stack already      Already        │
│   (no MA found)  (in progress)    deployed           installed      │
│                                   (CFN exists)       (helm + CFN)   │
│        │              │                 │                │          │
│        │              │                 │                │          │
│        ▼              ▼                 ▼                ▼          │
│   ┌─ Welcome ─────────────────────────────────────────────────────┐ │
│   │ "Manual" or "AI Agent"  (skip if already chosen this session) │ │
│   └─────────────────────────┬─────────────────────────────────────┘ │
│                             ▼                                        │
│   ┌─ Setup steps (progressive disclosure & detection) ────────────┐ │
│   │  1. AWS identity        — confirm or switch profile / region  │ │
│   │  2. Stage name          — default 'dev'                        │ │
│   │  3. Deployment scope    — Create VPC / Import VPC / Skip CFN   │ │
│   │  4. (if Import) VPC     — picker w/ live `describe-vpcs`       │ │
│   │  5. (if Import) Subnets — picker w/ NAT/IGW/isolated labels    │ │
│   │  6. (if isolated) VPC endpoints — auto-create or pick existing │ │
│   │  7. Source              — Published version / Build from src   │ │
│   │  8. TLS mode            — none / self-signed / PCA / new PCA   │ │
│   │  9. EKS access          — current principal pre-filled         │ │
│   │ 10. Advanced (optional) — namespace, image tag, node pools…    │ │
│   └────────────────────────┬───────────────────────────────────────┘ │
│                            ▼                                         │
│   ┌─ Review & launch ────────────────────────────────────────────┐  │
│   │  Show resolved bootstrap.sh invocation. Save state. Run.     │  │
│   │  Stream CFN events + helm install in real time.              │  │
│   └────────────────────────┬─────────────────────────────────────┘  │
│                            ▼                                         │
│   ┌─ Handoff ───────────────────────────────────────────────────┐   │
│   │  Manual → kubectl exec into migration-console-0             │   │
│   │  Agent  → install skill kit, launch chosen agent CLI        │   │
│   └─────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. Resumability & state

### 5.1 What state lives where

| State                                | Source of truth         | TUI behavior                              |
|--------------------------------------|-------------------------|-------------------------------------------|
| User's in-progress choices           | Local state file        | Save on every step; offer resume on launch|
| Whether AWS infra is deployed        | CFN `list-exports`      | Re-detect every launch — never trust stale|
| Whether helm release exists          | `helm status` on cluster| Re-detect after AWS identity is resolved  |
| Which AI agents are installed        | `command -v` on PATH    | Re-detect every launch (cheap)            |
| User's preferred mode / theme        | Persistent config file  | Carry across sessions                     |

**Principle:** local state is *user choices*, never *AWS facts*. AWS is
re-queried every launch.

### 5.2 State file layout

```
~/.config/opensearch-migration-assistant/
├── config.json                         # mode, theme, telemetry opt-in
└── sessions/
    ├── prod-us-east-1.json             # one file per (stage, region)
    ├── dev-us-east-1.json
    └── …
```

Each session file records: chosen flags, AWS account ID, region, stage,
last-completed step, the literal `aws-bootstrap.sh` argv it would run, and
a `status` field (`in_progress` | `bootstrapped` | `installed` | `failed`).

### 5.3 Launch routing

On launch the TUI runs detection in parallel and routes:

| Detected state                                                     | Default landing                          |
|--------------------------------------------------------------------|-------------------------------------------|
| No saved sessions, no CFN exports                                  | Welcome → Mode picker → Intent capture → Setup |
| Saved session in `in_progress`, AWS state agrees                   | "Resume?" prompt                          |
| Saved session in `in_progress`, AWS state diverges                 | **Diff-and-pick screen** (see below)      |
| CFN stack present, no helm release                                 | "Stack found — finish bootstrap?"         |
| CFN stack + helm release both present                              | "Already installed — open console?"       |
| Multiple stages found                                              | Stage picker                              |

### 5.4 Resume diff (locked r4)

When the local session and AWS disagree, we don't pick — we show:

```
┌─ Saved session and AWS state disagree ──────────────────────────────┐
│                                                                      │
│  What you saved                  What AWS shows now                  │
│  ──────────────────              ───────────────────                 │
│  Step:   helm install            Stack:    CREATE_COMPLETE           │
│  Status: in_progress             Cluster:  ACTIVE                    │
│  Saved:  4h 22m ago              Helm rel: deployed (45m ago)        │
│  Stage:  dev                     Stage:    dev                       │
│                                                                      │
│  Most likely: install completed without you (script run elsewhere?). │
│                                                                      │
│   ▸ Adopt AWS state — treat as installed, open console               │
│     Adopt local state — re-run install (will fail if release exists) │
│     Cancel and investigate                                           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 6. Progressive AWS detection

The bootstrap script discovers AWS state on demand (lists VPCs only when no
`--vpc-id` is given, lists subnets only when no `--subnet-ids` is given).
The TUI mirrors this — but eagerly, so the user sees options as they decide.

### 6.1 Detection ladder

Each step's detection is gated on the previous step's answer. We don't fetch
a list until we know the user needs it. We don't show a list larger than
what fits on screen without a search filter.

```
Step                     Triggers AWS API call(s)
───────────────────────  ────────────────────────────────────────────────
1. AWS identity          sts:GetCallerIdentity
2. Stage name            (no API call)
3. Deployment scope      cfn:ListExports                    ← detects "is MA already deployed?"
4. Import VPC?           ec2:DescribeVpcs (filtered by region)
5. Subnets               ec2:DescribeSubnets, ec2:DescribeRouteTables
                         (annotates each: NAT / IGW / isolated)
6. (isolated) endpoints  ec2:DescribeVpcEndpoints
7. Source / version      GET github.com latest release tag
8. TLS mode              acm-pca:ListCertificateAuthorities (only if PCA chosen)
9. EKS access principal  sts:GetCallerIdentity (re-use)
                         iam:GetRole / iam:GetUser (label only)
```

### 6.2 What "intelligent defaults" means concretely

For each decision, the TUI computes a recommendation grounded in
detected state, and presents the recommendation pre-selected — not silently
applied. Examples:

| Decision        | Default rule                                                                       |
|-----------------|------------------------------------------------------------------------------------|
| Region          | `aws configure get region` → fall back to `us-east-1`                              |
| Stage           | `dev`                                                                              |
| Deployment scope| If no MA exports found → "Create VPC". If exports found → "Skip CFN deploy"        |
| VPC             | None preselected. Disable VPCs in unsupported states.                              |
| Subnets         | Preselect 2 subnets in different AZs with NAT/IGW; warn if isolated chosen        |
| VPC endpoints   | If subnets isolated, preselect `s3,ecr,ecrDocker` (the three required)            |
| Source          | "Published version → latest"                                                       |
| TLS             | "self-signed" (matches script default)                                             |
| EKS access      | Current `sts:GetCallerIdentity` ARN                                                |
| Namespace       | `migration-assistant`                                                              |

### 6.3 Permission-graceful degradation

If a discovery call fails with `AccessDenied`, the TUI must:
1. Show *which* IAM action is missing (`ec2:DescribeVpcs`, etc).
2. Offer a manual-entry fallback (paste a VPC ID) without forcing the user
   to fix IAM first.
3. Record the gap so the review screen can surface "you'll need permission
   X for this to actually run."

---

## 7. Welcome screen

```
┌─ OpenSearch Migration Assistant CLI ─────────────────────────────────┐
│                                                                      │
│  Welcome. This tool helps you stand up the OpenSearch Migration      │
│  Assistant in your AWS account, then either drops you into the       │
│  migration console or hands off to an AI agent.                      │
│                                                                      │
│  Detected:                                                           │
│    AWS identity   123456789012 / arn:aws:iam::…/Admin                │
│    Region         us-east-1   (from `aws configure`)                 │
│    Existing MA    none found in us-east-1                            │
│    AI agents      claude-code  2.1.140  (latest 2.1.147)             │
│                   kiro-cli     0.4.1    (latest 0.5.0) ⚠ minor behind│
│                                                                      │
│  Saved sessions:  none                                               │
│                                                                      │
│  How do you want to drive the migration?                             │
│                                                                      │
│   ▸ Manual    — TUI walks setup, then drops you into a console shell │
│     AI Agent  — TUI walks setup, then launches an AI agent for you   │
│                                                                      │
│   [↑↓] choose   [enter] continue   [a] switch AWS account   [q] quit │
└──────────────────────────────────────────────────────────────────────┘
```

The detection summary is shown *here*, on the welcome screen, so the user
can verify their identity / region before committing to setup. Mistakes
caught on this screen save 10+ minutes of wrong-account deploys.

---

## 8. Setup wizard

A vertical step list on the left, the active step's form on the right.
Completed steps collapse to one line; the active step expands.

```
┌─ Setup ──────────────────────────────────────────────────────────────┐
│  1 ✓ AWS identity      123456789012 / us-east-1                     │
│  2 ✓ Stage             dev                                          │
│  3 ✓ Deployment scope  Import existing VPC                          │
│  4 ▸ VPC               ┌────────────────────────────────────────┐   │
│                        │ Searching VPCs in us-east-1…           │   │
│                        │                                        │   │
│                        │   vpc-0abc…  prod-vpc        10.0.0/16 │   │
│                        │ ▸ vpc-0def…  staging-vpc     10.1.0/16 │   │
│                        │   vpc-0xyz…  default                   │   │
│                        │                                        │   │
│                        │ [↑↓] pick   [/] filter   [m] manual    │   │
│                        └────────────────────────────────────────┘   │
│  5   Subnets                                                         │
│  6   Source                                                          │
│  …                                                                   │
│                                                                      │
│ [esc] back   [enter] confirm   [s] save & exit   [?] help            │
└──────────────────────────────────────────────────────────────────────┘
```

Pressing `[s]` at any step writes session state and quits cleanly.

### 8.1 Three pickers worth special attention

**VPC picker (step 4):** annotated rows with name + CIDR; manual-entry fallback.

**Subnet picker (step 5):** multi-select with route-table-derived labels —
this is the *highest-risk* step, because picking isolated subnets without
VPC endpoints causes deploys that fail 20 minutes in. Show the label
prominently:

```
   subnet-111…  us-east-1a  10.1.1.0/24  IGW (public)
 ▸ subnet-222…  us-east-1b  10.1.2.0/24  NAT
   subnet-333…  us-east-1c  10.1.3.0/24  ⚠ ISOLATED — no NAT/IGW
```

If the user selects an isolated subnet, the wizard inserts an *additional
step* (5b) for VPC endpoint creation rather than failing later.

**Source picker (step 7):** "Latest published" / "Specific version" /
"Build from source." If "Build from source," gate on `--build` requiring a
local checkout (`gradlew` exists), and surface that requirement *before*
the user gets stuck.

---

## 9. Review & launch

```
┌─ Review ─────────────────────────────────────────────────────────────┐
│  Equivalent command (the bootstrap script we'll run):               │
│                                                                      │
│   aws-bootstrap.sh \                                                 │
│     --deploy-import-vpc-cfn \                                        │
│     --stack-name MA-Dev \                                            │
│     --stage dev \                                                    │
│     --region us-east-1 \                                             │
│     --vpc-id vpc-0def… \                                             │
│     --subnet-ids subnet-222…,subnet-333… \                           │
│     --create-vpc-endpoints s3,ecr,ecrDocker \                        │
│     --version 3.2.1 \                                                │
│     --tls-mode self-signed \                                         │
│     --eks-access-principal-arn arn:aws:iam::…/Admin                  │
│                                                                      │
│  This will create:                                                   │
│    • CFN stack 'MA-Dev'  (~12 min)                                   │
│    • EKS cluster + node pool  (~6 min)                               │
│    • Helm release 'migration-assistant'  (~5 min)                    │
│    • 3 VPC endpoints in vpc-0def…                                    │
│                                                                      │
│  Estimated cost: ~$X/day while running. [d]etails                    │
│                                                                      │
│  [enter] launch   [b] back   [c] copy command   [s] save & exit      │
└──────────────────────────────────────────────────────────────────────┘
```

Showing the literal command we'd run is non-negotiable — it lets the user
trust the TUI (since they can verify each flag), and it gives DevOps users
something they can paste into CI without re-running the wizard.

---

## 10. Live deploy view

Because we re-implemented bootstrap.sh natively (locked **C**), the TUI
drives each phase directly via AWS SDK + helm Go bindings, and renders
true progress without parsing.

```
┌─ Deploying Migration Assistant ──────────────────────────────────────┐
│                                                                      │
│  Phase 1/3: CloudFormation     [████████████░░░░░░] 14:23 elapsed   │
│   ├ AWS::EC2::VPC              CREATE_COMPLETE       12s            │
│   ├ AWS::EC2::Subnet (×3)      CREATE_COMPLETE       18s            │
│   ├ AWS::EKS::Cluster          CREATE_IN_PROGRESS    8m44s ←        │
│   └ AWS::IAM::Role             CREATE_COMPLETE       3s             │
│                                                                      │
│  Phase 2/3: Image mirroring    PENDING                              │
│  Phase 3/3: Helm install       PENDING                              │
│                                                                      │
│  [l] full log   [esc] background (deploy keeps running)              │
└──────────────────────────────────────────────────────────────────────┘
```

**Backgrounding behavior.** Because there's no subprocess to detach
from, "background" means: the TUI persists in-flight state to the
session file (CFN stack ARN + currently-active phase + monotonic step
counter) and exits. **AWS keeps deploying** — the CFN stack is already
running server-side; helm install is the only client-side phase.
Re-launching the TUI re-attaches by detecting `*_IN_PROGRESS` stack
state and resuming the event stream from the last seen event ID.

If ESC is hit *during* the helm-install phase (the only client-driven
phase), the TUI offers two choices:
- **Detach without aborting:** spawn a detached background process
  (a small reaper binary) that finishes the helm install and writes
  result to the session file. TUI exits.
- **Cancel:** runs `helm uninstall` on the partial release.

Real cost of ESC differs per phase; the prompt makes that explicit.

---

## 11. Manual handoff (mode = Manual)

When everything is `CREATE_COMPLETE` and helm reports installed:

```
┌─ Ready ──────────────────────────────────────────────────────────────┐
│  Migration Assistant is installed.                                   │
│                                                                      │
│  Open the migration console (interactive shell in pod):              │
│   ▸ Open now                                                         │
│     Show me the kubectl command (don't open)                         │
│     Save & exit (open later with `migration-assistant resume`)       │
│                                                                      │
│  Reference:                                                          │
│   kubectl --context=… -n migration-assistant exec -it \              │
│     migration-console-0 -- /bin/bash                                 │
└──────────────────────────────────────────────────────────────────────┘
```

"Open now" `exec`s `kubectl` — the TUI process is replaced by `kubectl
exec`. When the user `exit`s the pod shell, they return to their
original shell (no TUI). Re-running `migration-assistant` detects the
already-installed state and offers to re-open the console.

---

## 12. Agent handoff (mode = AI Agent)

Detect → install → write skill kit + handoff brief → `exec` agent CLI →
TUI exits. After `exec`, the agent owns the session.

### 12.1 Agent CLIs supported (v1)

| CLI         | Detection      | Adapter writes to              | Launch                                                |
|-------------|----------------|--------------------------------|-------------------------------------------------------|
| Kiro        | `kiro-cli`     | `./.kiro/` (from upstream tarball) | `kiro-cli chat --agent opensearch-migration "@start"` |
| Claude Code | `claude`       | `./.claude/skills/opensearch-migration/` | `claude` in the work dir                              |

Q Developer and Cline are deferred to v2. Cline in particular is
VS-Code-resident and doesn't fit a TUI handoff cleanly.

### 12.2 The skill kit

We ship an **agent-agnostic skill bundle** (`opensearch-migration-skills/`,
schema in §0.2) and write per-agent adapter files at install time.
Source of truth is upstream `.kiro/` from the OpenSearch project; our
release pipeline reverse-translates it into our agent-agnostic format.

For Kiro specifically, since upstream ships `.kiro/` natively, we
*prefer the upstream tarball directly* and skip the agent-agnostic
detour — this keeps Kiro's experience pixel-perfect with what the
upstream `bootstrap-kiro-agent.sh` would have produced.

### 12.3 Global vs. local skills (default: local — locked F)

The skill kit lands in the launch cwd by default — matches Kiro upstream
(`./kiro-migration-agent/.kiro/`). Power users can opt into global
(`~/.<agent>/...`) at install time.

```
Where should the migration skill kit live?
   ▸ Local  (./opensearch-migration-agent/...)
     Global (~/.claude/skills/opensearch-migration/)

  Local keeps the agent's worldview scoped to migration work in this
  directory. Global is convenient if you'll do many migrations from
  many places.
```

### 12.4 The handoff brief (locked Z)

Before `exec`ing the agent, the TUI writes `HANDOFF.md` to the workdir.
YAML frontmatter holds machine-parseable state; the body is the user's
free-text goal from intent capture (§3).

```markdown
---
ma_version: 3.2.1
aws_account: "123456789012"
region: us-east-1
eks_cluster: migration-eks-cluster-dev-us-east-1
namespace: migration-assistant
stage: dev
source:
  endpoint: https://es.old-co.internal:9200
  engine: Elasticsearch
  engine_version: "7.10"
  auth_method: basic
  auth_keychain_id: ma-cli-source-creds-abc123
  approx_size: ~2 TB / 800 indices
target:
  type: new-opensearch-domain
  endpoint: <pending discovery>
console_exec: |
  kubectl --context=migration-eks-cluster-dev-us-east-1 \
    -n migration-assistant exec -it \
    migration-console-0 -- /bin/bash
written_at: 2026-05-21T19:42:00Z
schema_version: 1
---

# Migration goal

Migrate prod search index from on-prem ES 7.10 to OpenSearch 2.x with
zero downtime. Replicate live writes during cutover.
```

**Why frontmatter + body:** the user can `cat HANDOFF.md` and audit
what we passed to the agent. Trust comes from legibility. JSON would
be more rigid but unreadable at a glance.

**The `@start` skill** (shipped, static, in the agent-agnostic bundle)
instructs the agent: "Before responding to anything else, read
`./HANDOFF.md` for migration context. Treat the frontmatter as ground
truth and the body as the user's stated intent."

**Credentials are never inlined.** The `auth_keychain_id` field is a
reference to an OS-keychain item (locked **X**); the agent prompts the
OS keychain for the actual secret if/when it needs to authenticate.

### 12.5 Agent CLI not installed

If the user picks "AI Agent" and no supported agent CLI is on PATH:

```
No supported AI agent CLI detected.

Install one — recommended: Kiro (the official agent for this migration kit).
   ▸ Install kiro-cli  (curl + binary install, no sudo for $HOME prefix)
     Install Claude Code  (npm install -g @anthropic-ai/claude-code)
     I'll install one myself, retry detection
     Switch to Manual mode
```

We never silently install. Always confirm; always show the exact
command we're about to run before running it.

---

## 13. Idempotency, update, uninstall

### 13.1 Re-running when stack already exists

The bootstrap script hard-fails if the helm release already exists. The
TUI catches this earlier and offers four paths:

```
Migration Assistant is already installed in 123456789012 / us-east-1.
   ▸ Open the console
     Switch to AI Agent mode
     Reinstall (uninstall helm release + redeploy)
     Uninstall completely (helm + CFN stack)
```

### 13.2 Update path

Currently out of scope for the bootstrap script — there is no `--upgrade`
flag. **Open Question G**: do we need to support upgrading from MA 3.2.0 →
3.2.1 in v1, or is "uninstall + reinstall" acceptable? The latter destroys
in-flight migration state, which is bad. Recommendation: **defer upgrade
flow to v2; document the limitation in v1**.

### 13.3 Uninstall

Reverse of install. The destructive step list:

1. `helm uninstall migration-assistant -n migration-assistant`
2. `aws cloudformation delete-stack --stack-name <stack>`
3. (If created by us) Delete VPC endpoints
4. (If `pca-create`) Delete the AWS Private CA — **WARNING: 7-day waiting period**

Each step gets its own confirmation. The PCA deletion gets a *second*
confirmation that requires typing the CA ID.

---

## 14. Information density: one mode, two volumes

You said you want this usable by everyone. I'm dropping the "skill mode
toggle" idea from v0.1 because it's too ambitious for this surface. Instead:

- The TUI uses **one consistent layout**.
- **Verbosity scales with context**: detection messages are quieter when
  things succeed, louder when they fail.
- **Help is on-demand**: `?` opens a help overlay; we don't crowd every
  screen with explanatory text.

→ **Open Question H:** Is one density right, or do you still want a
toggle? My current call: one density. We can revisit if early users find it
too dense or too sparse.

---

## 15. Keybindings (working set)

| Key       | Action                                                   |
|-----------|----------------------------------------------------------|
| `enter`   | Confirm current step / launch / open console             |
| `↑↓`      | Navigate within a list                                   |
| `space`   | Toggle in multi-select (subnets, VPC endpoints)          |
| `/`       | Filter the current list                                  |
| `esc`     | Back one step / dismiss modal / background a deploy      |
| `b`       | Back one step (alias)                                    |
| `s`       | Save session and quit                                    |
| `a`       | Switch AWS account / profile                             |
| `?`       | Help overlay                                             |
| `q`       | Quit (confirms if any deploy is in-flight)               |
| `^c`      | Same as `q`                                              |

---

## 16. Output / scriptability

Two surfaces:

- **TTY mode** (default): the TUI we've described.
- **`--print-command`**: take saved session, print the equivalent
  `aws-bootstrap.sh` invocation, exit. For users who want a CI-pasteable
  command after one-time interactive setup.

Out of scope for v1: a full non-interactive flag-driven CLI mirror. The
upstream script already is one — we don't need to reinvent it.

---

## 17. Open questions (consolidated)

Resolved questions are in §0 (Locked decisions).

**H.** Single density, or Guided/Expert toggle? (*Recommendation: single.*)

**J.** Multi-region / multi-stage in one TUI session — switch within a
session, or quit and relaunch? (*Recommendation: switch via `a` keybind;
each (account, region, stage) gets its own session file. Note: with
named-subdir workdirs (locked R), each (account, region) is already a
separate folder, so this is mostly about switching account within a
single TUI launch vs. quit-and-relaunch.*)

**T. (from r3)** Adapter divergence between Kiro and Claude Code: if
upstream adds a Kiro-specific feature (e.g. `@hook` triggers), and we're
prefer-upstream-for-Kiro but agent-agnostic-for-others, the two agents
will have *different capabilities* on the same migration. Is that
acceptable, or do we cap Kiro at what Claude Code can do?

**U. (from r3)** Co-shipping from `opensearch-migrations` means our
release process is gated on theirs. Two implications:
- We can't ship a TUI bug fix faster than their release cadence.
- The OpenSearch project owns our release tooling.
Is this trade-off worth it? Or do we want a private patch lane (e.g.
"TUI 1.0.1 ships independently for non-functional fixes")?

**X.** Source-cluster auth credentials from intent capture (§3).
*Recommendation: OS keychain (macOS Keychain, secret-service,
Credential Manager); `.ma-state.json` and `HANDOFF.md` hold only the
keychain item ID. The agent prompts the OS keychain when it needs the
credential.* Confirm?

(Z resolved: `HANDOFF.md` format locked — see §12.4.)
(R, S, L resolved — see §0 locked decisions.)
(AA, CC resolved — see §0 locked decisions. BB dropped from scope.)
(DD resolved by r9 — tag-pinned raw repo with TODO to promote, see §0.7.)

---

## 18. Anti-goals

- Not a replacement for the migration console itself (we hand off to it).
- Not an authoring tool for migration playbooks.
- Not a general AWS console replacement.
- Not opinionated about which agent CLI is "correct" — we list what's there.
- Not a backup / data-recovery tool for the source cluster.
