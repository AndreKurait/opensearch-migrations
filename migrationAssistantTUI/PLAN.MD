# Enterprise-Grade Bubble Tea v2 TUI — Plan

A blueprint for a maintainable, testable TUI built on **Bubble Tea v2 + Bubbles v2 + Lipgloss v2**. Patterns are derived from a study of `charmbracelet/crush` (a stable, large-scale v2 codebase) plus the official v2 examples and `teatest/v2` testing package. Domain/UX is intentionally left abstract — fill in `feature/*` packages once the product surface is known.

---

## 1. Goals and constraints

| Goal | How the plan achieves it |
| --- | --- |
| Maintainable as the surface grows | Strict three-layer split (`cmd` → `app` → `ui`); `ui` does no I/O |
| High test coverage with low test friction | Constructor-injected services + small interfaces; table-driven unit tests on `Update`; golden snapshots only at component boundaries |
| No accidental v1↔v2 drift | One v2 import path everywhere (`charm.land/...`); `tea.View` (struct) and `tea.KeyPressMsg` (interface) used from day 0 |
| Theming without rewrites | One `Styles` struct + theme constructor functions, fed semantic tokens from a palette package |
| Deterministic CI | `tea.WithColorProfile`, fixed window size in tests, golden files normalized via `x/exp/golden` |
| Clear evolution path | Start with conventional one-Model-per-component; document the imperative-subcomponent escape hatch (Crush's choice) for when it pays off |
| No "screen-corrupting" debug prints | File-only logger wired before `Program.Run()`; `fmt.Print*` is a lint error in `internal/ui/...` |
| Bindings and help that never desync | One `KeyMap` struct = single source of truth for keys *and* help text |
| Clean shutdown without panics | Explicit publisher → broker lifecycle; goroutines select on `ctx.Done()`; broker close is idempotent |
| Layout math that doesn't break on emoji/CJK | `lipgloss.Width(s)` only; `len()` and `RuneCountInString` are bugs |
| First-class member of a Java/Gradle monorepo | Native Go build internally + thin Gradle wrapper at `tui/build.gradle.kts`; toolchains pinned in `mise.toml`; coverage flows to Codecov alongside Java JaCoCo |

---

## 2. Stack & version pins

Stable v2 ecosystem (released 2026-02-24). All three Charm libraries moved to the `charm.land/...` vanity path; the GitHub paths still work but the canonical names are `charm.land`.

`go.mod`:

```go
module example.com/tui          // replace

go 1.25.0                       // bubbletea/v2 requires 1.25+

require (
    charm.land/bubbletea/v2 v2.0.6
    charm.land/bubbles/v2   v2.1.0
    charm.land/lipgloss/v2  v2.0.3
)

require (
    // testing
    github.com/charmbracelet/x/exp/teatest/v2 latest
    github.com/charmbracelet/x/exp/golden     latest
    github.com/stretchr/testify               v1.11.1

    // optional
    github.com/charmbracelet/colorprofile v0.4.3   // explicit profile control
    github.com/charmbracelet/x/ansi       v0.11.7  // raw escape sequences
)
```

Notes:
- **Don't mix v1 and v2** Charm packages — they share types (`tea.Msg`, `lipgloss.Color`) that diverged.
- `lipgloss.Color` is now a **function** returning `image/color.Color`, not a string type.
- `Renderer` was removed from lipgloss v2 — `Style` is a pure value, no global state.

---

## 3. Directory layout

Follows Crush's convention: everything under `internal/` so nothing is part of a public API contract; `cmd/` is the single entry-point binary.

```
.
├── cmd/
│   └── tui/
│       └── main.go              # tiny: parse flags, build App, run Program
├── internal/
│   ├── app/                     # App struct: wires services + pubsub broker
│   │   ├── app.go
│   │   └── app_test.go
│   ├── config/                  # config loading, validation
│   ├── log/                     # structured logger setup (writes to file in TUI mode)
│   ├── pubsub/                  # generic Broker[T] for async → UI events
│   │   ├── broker.go
│   │   └── broker_test.go
│   ├── feature/                 # business/domain services (one subdir per bounded context)
│   │   └── …                    # e.g. feature/user, feature/job — TODO: domain
│   └── ui/
│       ├── AGENTS.md            # architecture note for humans + AI contributors
│       ├── ui.go                # root model: Init/Update/View, page state machine
│       ├── ui_test.go
│       ├── workspace/           # Workspace interface — façade over *app.App for testability
│       ├── common/              # shared context: *Styles, Workspace, sizes
│       ├── styles/              # Styles struct, themes, palette tokens, icons
│       ├── keys/                # global key.Map; per-page keymaps live with the page
│       ├── msg/                 # cross-cutting tea.Msgs (errors, status, navigation)
│       ├── components/          # reusable building blocks (input, list, table wrappers)
│       │   └── <component>/
│       │       ├── component.go
│       │       ├── component_test.go
│       │       └── testdata/
│       ├── pages/               # one subpackage per page; each owns its Model
│       │   └── <page>/
│       │       ├── page.go
│       │       ├── page_test.go
│       │       └── keys.go
│       └── dialog/              # stack-based modal overlay
└── internal/testutil/           # teatest harness, golden helpers, color-profile init
    └── teaharness.go
```

Rules:
1. **`internal/ui` does no I/O.** No DB calls, no HTTP, no `os.ReadFile`. All async work flows in via the pubsub broker as `tea.Msg`s.
2. **`feature/*` packages know nothing about Bubble Tea.** They expose plain Go services (`Service` interfaces returning typed values + errors). The TUI consumes them through `Workspace`.
3. **`pages/*` and `components/*` are the only places that import bubbletea/bubbles directly.** Other layers are TUI-agnostic.
4. **One package = one concern.** Don't add a kitchen-sink `util` package; if a helper has no obvious home, it doesn't exist yet.

---

## 4. Architecture

### 4.1 Layering

```
cmd/tui/main.go
   │
   ▼
app.New(ctx, cfg)  ──────────►  pubsub.Broker[tea.Msg]
   │  builds Service implementations (DB clients, HTTP clients, …)
   │  publishes domain events into the broker
   ▼
ui.New(workspace, options)  ──►  tea.Program
   │  subscribes to broker in Init; dispatches to pages in Update
   ▼
ui/pages/<page>  ──►  ui/components  ──►  ui/styles
```

### 4.2 Dependency injection: `App` and `Workspace`

`App` is the composition root for production. `Workspace` is the interface the UI sees — it's swapped for a fake in tests.

```go
// internal/app/app.go
type App struct {
    Cfg     *config.Config
    Log     *slog.Logger
    Events  *pubsub.Broker[tea.Msg]
    // services — populated in New()
    Users   feature.UserService
    Jobs    feature.JobService
    // …
}

// internal/ui/workspace/workspace.go
type Workspace interface {
    Config() *config.Config
    Log()    *slog.Logger
    Events() <-chan tea.Msg     // subscriber channel; multi-subscribe via broker

    Users() feature.UserService
    Jobs()  feature.JobService
}
```

The TUI **never imports `*app.App`** — only `Workspace`. Tests build a `fakeWorkspace` that returns canned services. This is the single biggest lever for test ergonomics.

### 4.3 Async events: `pubsub.Broker[tea.Msg]`

All service-side state changes that the UI cares about flow through one typed broker. The UI subscribes once in `Init` and processes them in `Update` like any other `tea.Msg`. The full lifecycle (who closes what, how publishers avoid send-on-closed-channel) is in **§9 Concurrency & shutdown**.

```go
// internal/pubsub/broker.go — drain-safe, non-blocking, idempotent close
type Broker[T any] struct {
    mu   sync.RWMutex
    subs map[chan T]struct{}
    done chan struct{}
    buf  int
}
func New[T any](buf int) *Broker[T] { return &Broker[T]{subs: map[chan T]struct{}{}, done: make(chan struct{}), buf: buf} }
func (b *Broker[T]) Subscribe(ctx context.Context) <-chan T  // closes when ctx cancels OR broker closes
func (b *Broker[T]) Publish(v T)                              // non-blocking; drops if subscriber buffer is full
func (b *Broker[T]) Close()                                   // idempotent; closes all subscriber channels
```

In the UI:

```go
func (m Model) Init() tea.Cmd {
    return waitForEvent(m.events)   // re-issued from Update on each msg
}
func waitForEvent(ch <-chan tea.Msg) tea.Cmd {
    return func() tea.Msg {
        msg, ok := <-ch
        if !ok { return shutdownMsg{} }   // broker closed → exit gracefully
        return msg
    }
}
// In Update on any msg routed via the broker:
//   handle(msg); return tea.Batch(handlerCmd, waitForEvent(m.events))
```

This is the canonical "long-running channel into Update" pattern from `bubbletea/examples/realtime`, hardened with the `, ok` check so a broker close drains cleanly instead of feeding the zero value into Update.

### 4.4 Model composition — recommended approach

**Default: conventional Bubble Tea — every page and reusable component is its own `tea.Model`.** This maximizes per-unit testability; you can call `child.Update(msg)` directly in tests and table-drive each component.

```go
// internal/ui/ui.go
type Model struct {
    ws       workspace.Workspace
    common   *common.Common
    page     pageID            // landing | list | detail | …
    pages    map[pageID]tea.Model
    keys     keys.Global
    help     help.Model
    width, height int
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
    switch msg := msg.(type) {
    case tea.WindowSizeMsg:
        m.width, m.height = msg.Width, msg.Height
        // forward to active page
    case tea.KeyPressMsg:
        if key.Matches(msg, m.keys.Quit) { return m, tea.Quit }
        if key.Matches(msg, m.keys.Help) { m.help.ShowAll = !m.help.ShowAll; return m, nil }
    case msg.NavigateMsg:
        m.page = msg.To; return m, m.pages[m.page].Init()
    }
    var cmd tea.Cmd
    m.pages[m.page], cmd = m.pages[m.page].Update(msg)
    return m, cmd
}
```

**Escape hatch (when to deviate):** Crush adopts an "imperative sub-component" pattern where the root is the *only* `tea.Model` and children are plain structs with methods (`chat.SetMessages`, `chat.HandleMouseDown`). This is faster (no message forwarding to dozens of children) and easier to reason about for highly dynamic content (chat lists with thousands of items). Adopt this **only after profiling shows propagation overhead**, or for a specific high-churn component (e.g. a chat-style list with versioned items). Document the deviation in `internal/ui/AGENTS.md`.

### 4.5 Component interface vocabulary

For lists, tables, and other containers of heterogeneous content, define small interfaces that items implement à la carte:

```go
type Item interface {
    Render(width int) string
    ID() string
}
type Focusable    interface { SetFocused(bool) }
type Highlightable interface { Highlight() lipgloss.Style }
type Versioned    interface { Version() uint64 }    // bump on mutation; enables render memoization
```

Versioning + a list-level cache (key = `id|version|width`) lets you skip re-renders for stable items — important for long lists.

### 4.6 Dialog overlay

A stack-based modal layer. The active dialog gets first crack at messages; it returns an `Action` (close, replace, propagate) so dialogs stay decoupled from the host.

```go
type Dialog interface {
    ID() string
    Init() tea.Cmd
    Update(tea.Msg) (Dialog, tea.Cmd, Action)
    View(width, height int) string
}
type Action int
const ( ActionPropagate Action = iota ; ActionClose ; ActionReplace )
```

Root model holds `[]Dialog`; if non-empty, top-of-stack handles input first. Render order: page → overlay (`lipgloss.Place` over backdrop).

---

## 5. Bubble Tea v2 specifics (the parts that bite if you don't know)

| What | v1 | v2 |
| --- | --- | --- |
| Import path | `github.com/charmbracelet/bubbletea` | `charm.land/bubbletea/v2` |
| `View()` signature | `View() string` | **`View() tea.View`** (struct with AltScreen, MouseMode, Cursor, WindowTitle, ProgressBar, KeyboardEnhancements) |
| Key message | `tea.KeyMsg` (struct: `Type`, `Runes`, `Alt`) | `tea.KeyMsg` is now an **interface**; use `tea.KeyPressMsg` (`Code`, `Text`, `Mod` bitmask) |
| Mouse message | `tea.MouseMsg` (struct) | Interface: `MouseClickMsg`, `MouseReleaseMsg`, `MouseWheelMsg`, `MouseMotionMsg` |
| Alt screen, mouse, focus | `tea.WithAltScreen()` program option | Set fields on returned `tea.View` (declarative per-frame) |
| Toggle commands (`tea.EnterAltScreen`, `tea.HideCursor`, `tea.SetWindowTitle`) | Available | **Removed** — set on `tea.View` |
| Sequenced commands | `tea.Sequentially` | `tea.Sequence` |
| Window size request | `tea.WindowSize()` cmd | `tea.RequestWindowSize` msg |
| Program start | `p.Start()` | **`p.Run()`** |
| Lipgloss color | `lipgloss.Color("…")` (string type) | Function returning `image/color.Color` |
| Lipgloss `Renderer` | Used to manage output | **Removed**; `Style` is pure value |
| Bubbles geometry | `m.Width = 80` | `m.SetWidth(80)` / `m.Width()` getter |
| Bubbles constructors | `viewport.New(80, 24)` | `viewport.New(viewport.WithWidth(80), viewport.WithHeight(24))` |
| Bubbles styles | Implicit dark | Explicit: `help.DefaultDarkStyles()` / `DefaultLightStyles()` |

Snippet — the canonical v2 root:

```go
import tea "charm.land/bubbletea/v2"

func (m Model) Init() tea.Cmd { return nil }

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
    switch msg := msg.(type) {
    case tea.KeyPressMsg:
        if msg.String() == "ctrl+c" { return m, tea.Quit }
    case tea.WindowSizeMsg:
        m.width, m.height = msg.Width, msg.Height
    }
    return m, nil
}

func (m Model) View() tea.View {
    v := tea.NewView(m.render())
    v.AltScreen   = true
    v.MouseMode   = tea.MouseModeCellMotion
    v.ReportFocus = true
    v.WindowTitle = "tui"
    return v
}
```

**Heads-up on SSH/Wish:** `os.Getenv` returns the *server* environment. Listen for `tea.EnvMsg` if you ever plan to host this over Wish.

**Unicode display width — `lipgloss.Width` always.** `len(s)`, `utf8.RuneCountInString(s)`, and `lipgloss.Width(s)` give different answers for the same string:

| String                    | `len(s)` | `RuneCountInString(s)` | `lipgloss.Width(s)` |
|---------------------------|---------:|-----------------------:|--------------------:|
| `"héllo"`                 |     6    |             5          |        5            |
| `"日本"` (CJK)            |     6    |             2          |        4            |
| `"👨‍👩‍👧"` (ZWJ family)    |    18    |             5          |        2            |
| `"\x1b[31mhi\x1b[0m"` (ANSI) |  13   |            13          |        2            |

`lipgloss.Width` is multi-line-aware (returns max line width) and strips ANSI escapes (`lipgloss/v2.0.3/size.go:15`). It delegates to `github.com/charmbracelet/x/ansi.StringWidth` which is grapheme-aware.

Use exclusively:

```go
w := lipgloss.Width(s)                                  // measure
out := lipgloss.NewStyle().Width(n).Render(s)           // pad/truncate to n
out = ansi.Truncate(s, n, "…")                          // raw truncate with tail (no styling)
```

Lipgloss v2 does not export a top-level `Truncate`; use `github.com/charmbracelet/x/ansi.Truncate` (the same function `lipgloss/style.go:510` calls internally). A lint rule banning `len(` inside `internal/ui/...` is reasonable.

---

## 6. Logging when stdout IS the screen

In TUI mode, anything that writes to stdout/stderr corrupts the screen — that includes every `fmt.Println` debug statement you've ever used. Wire a file-only logger **before** `tea.NewProgram(...).Run()` and never look at stdout again until the program exits.

### 6.1 Setup pattern (slog, not `tea.LogToFile`)

`tea.LogToFile(path, prefix)` exists (`bubbletea/v2.0.6/logging.go:22`) but its `LogOptionsSetter` interface (`SetOutput(io.Writer); SetPrefix(string)`) is incompatible with `slog.Logger` — slog has neither method. The recommended pattern is to ignore `LogToFile` and configure slog directly:

```go
// internal/log/log.go
func Setup(verbose bool) (*os.File, error) {
    dir, _ := os.UserCacheDir()                                  // ~/Library/Caches | %LocalAppData% | ~/.cache
    path := filepath.Join(dir, "myapp", "log", "myapp.log")
    _ = os.MkdirAll(filepath.Dir(path), 0o755)
    f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600) // truncate-on-launch
    if err != nil { return nil, err }
    lvl := slog.LevelInfo
    if verbose || os.Getenv("LOG_LEVEL") == "debug" { lvl = slog.LevelDebug }
    slog.SetDefault(slog.New(slog.NewJSONHandler(f, &slog.HandlerOptions{Level: lvl, AddSource: true})))
    return f, nil // caller defers f.Close() AFTER Program.Run() returns
}
```

`cmd/tui/main.go` calls this *before* `app.New(...)`, then defers `f.Close()` after the program exits. Crush follows the same pattern (`internal/log/log.go:17`, `internal/cmd/root.go:287`) — they additionally use `gopkg.in/natefinch/lumberjack.v2` for size-based rotation (10 MB, 30-day retention). For a fresh app, truncate-on-launch is enough until logs become a debugging asset worth preserving.

### 6.2 Modes & flags

| Mode | When | Output destination |
|---|---|---|
| TUI (default) | Interactive run | File only; never stdout |
| Headless / scripting (`--no-tui`) | CI invocations, `--screenshot`, one-shot subcommands | stderr in plain text via `charm.land/log/v2` or `slog.NewTextHandler(os.Stderr)` |
| `--verbose` / `LOG_LEVEL=debug` | Both modes | Lower threshold to `slog.LevelDebug`; log destination unchanged |

### 6.3 Lint rule

Add to `.golangci.yml`:

```yaml
forbidigo:
  forbid:
    - p: '^fmt\.Print(ln|f|)$'
      msg: "Use slog; fmt.Print* corrupts the TUI screen."
issues:
  include:
    - path: 'internal/ui/'
      linters: [forbidigo]
```

This catches the most common screen-corruption bug at PR time.

### 6.4 Don't forget: panics

`recover` in your top-level `main` should restore the terminal *before* printing the panic — `tea.Program.RestoreTerminal()` is the right call. Otherwise the panic stack trace lands on the alt-screen and disappears when the terminal exits alt-screen mode, leaving the user with nothing.

```go
defer func() {
    if r := recover(); r != nil {
        if program != nil { _ = program.RestoreTerminal() }
        slog.Error("panic", "value", r, "stack", string(debug.Stack()))
        fmt.Fprintf(os.Stderr, "fatal: %v\n", r)
        os.Exit(2)
    }
}()
```

---

## 7. Error UX: toast vs status-bar vs modal

A TUI has three honest places to surface an error; pick the right one by severity. Crush demonstrates the pattern with one typed `InfoMsg{Type, Msg, TTL}` plus a single status-bar consumer (`internal/ui/util/util.go:30`, `internal/ui/model/status.go:67`).

### 7.1 The taxonomy

| Severity | Slot | Lifetime | Examples |
|---|---|---|---|
| `SevToast` | Top-right ephemeral notification | TTL 3-5s, auto-clears | "Saved", "Copied to clipboard", "Connected" |
| `SevStatus` | Persistent status-bar line | Until next event of equal/higher severity | "1 unsynced item", non-fatal warnings, recoverable errors |
| `SevModal` | Centered blocking dialog | Until user dismisses | Fatal errors, destructive confirms, network unreachable |

### 7.2 One message type, one router

```go
// internal/ui/msg/error.go
type Sev int
const ( SevToast Sev = iota; SevStatus; SevModal )

type ErrorMsg struct {
    Err      error
    Severity Sev
    Title    string         // shown verbatim; defaults to Err.Error() if empty
    TTL      time.Duration  // SevToast only; default 4s if zero
}

func ReportErr(err error, s Sev, title string) tea.Cmd {
    return func() tea.Msg { return ErrorMsg{Err: err, Severity: s, Title: title} }
}
```

```go
// in root Update
case msg.ErrorMsg:
    slog.Error("ui.error", "err", msg.Err, "sev", msg.Severity)
    switch msg.Severity {
    case msg.SevToast:
        m.toast = m.toast.Push(msg)
        return m, tea.Tick(durationOr(msg.TTL, 4*time.Second), clearToast)
    case msg.SevStatus:
        m.status.Set(msg)
        return m, nil
    case msg.SevModal:
        m.dialogs.Push(dialog.NewError(msg))
        return m, nil
    }
```

Always log the underlying `err` via slog before deciding what to render — the user-visible message is summarized but the file log gets the stack-grade detail.

### 7.3 Truncation matters

Status-bar messages must respect width. Use `ansi.Truncate(line, width, "…")` (Crush does this at `status.go:105`) — never `line[:width]` (slices bytes, not cells).

### 7.4 Never panic in `Update`

A panic inside `Update` kills the program with the terminal in a wrecked state. Guard the seams (callers from goroutines or external code) with explicit error returns, and rely on the top-level `recover` from §6.4 as a last resort.

---

## 8. Keymap as single source of truth

Without enforcement, keys defined in `Update` and help text defined in the help screen *will* desync within weeks. The fix: every binding lives in one struct, carries its help text via `key.WithHelp`, and the help screen consumes that struct.

### 8.1 The pattern

`bubbles/v2.1.0/key/key.go` provides `key.Binding` (keys + help) and `key.Matches[Key fmt.Stringer](k Key, b ...Binding) bool` (line 130 — generic; works with `KeyPressMsg` and `KeyReleaseMsg`). `bubbles/v2.1.0/help/help.go` defines `KeyMap interface { ShortHelp() []key.Binding; FullHelp() [][]key.Binding }` (line 18) consumed by `help.Model.View(km KeyMap)`.

Crush uses one root `KeyMap` struct with embedded sub-structs per page (`internal/ui/model/keys.go:5`):

```go
// internal/ui/keys/keys.go
type KeyMap struct {
    Global struct {
        Quit, Help, Back key.Binding
    }
    Landing struct {
        Start key.Binding
    }
    List struct {
        Up, Down, Open, Refresh key.Binding
    }
    // …one sub-struct per page
}

func Default() KeyMap {
    var k KeyMap
    k.Global.Quit = key.NewBinding(key.WithKeys("q", "ctrl+c"), key.WithHelp("q", "quit"))
    k.Global.Help = key.NewBinding(key.WithKeys("?"),           key.WithHelp("?", "help"))
    k.Global.Back = key.NewBinding(key.WithKeys("esc"),         key.WithHelp("esc", "back"))
    k.Landing.Start = key.NewBinding(key.WithKeys("enter"),     key.WithHelp("enter", "start"))
    k.List.Up    = key.NewBinding(key.WithKeys("up", "k"),      key.WithHelp("↑/k", "up"))
    k.List.Down  = key.NewBinding(key.WithKeys("down", "j"),    key.WithHelp("↓/j", "down"))
    k.List.Open  = key.NewBinding(key.WithKeys("enter"),        key.WithHelp("enter", "open"))
    k.List.Refresh = key.NewBinding(key.WithKeys("r"),          key.WithHelp("r", "refresh"))
    return k
}
```

### 8.2 Focus-aware help

The root model selects which `help.KeyMap` is rendered based on the focused page:

```go
type Focus int
const ( FocusLanding Focus = iota; FocusList; /* … */ )

func (k KeyMap) Help(f Focus) help.KeyMap { return pageMap{km: k, focus: f} }

type pageMap struct{ km KeyMap; focus Focus }

func (p pageMap) ShortHelp() []key.Binding {
    g := p.km.Global
    switch p.focus {
    case FocusLanding: return []key.Binding{p.km.Landing.Start, g.Help, g.Quit}
    case FocusList:    return []key.Binding{p.km.List.Up, p.km.List.Down, p.km.List.Open, g.Help, g.Quit}
    }
    return []key.Binding{g.Help, g.Quit}
}

func (p pageMap) FullHelp() [][]key.Binding {
    g := p.km.Global
    switch p.focus {
    case FocusList:
        return [][]key.Binding{
            {p.km.List.Up, p.km.List.Down},
            {p.km.List.Open, p.km.List.Refresh},
            {g.Back, g.Help, g.Quit},
        }
    }
    return [][]key.Binding{{g.Help, g.Quit}}
}

// Root view: m.help.View(m.keys.Help(m.focus))
// Root Update: if key.Matches(msg, m.keys.Global.Quit) { return m, tea.Quit }
```

`key.Matches(msg, m.keys.Global.Quit)` works at any layer because the binding is shared by value-equal copies — no global registry, no dispatch table, just a struct.

### 8.3 Configurable bindings (later)

When users want to remap keys, load overrides into the same struct from a config file before constructing the root model:

```go
type Override map[string][]string  // "List.Open" → ["o", "enter"]

func (k *KeyMap) Apply(o Override) {
    if v, ok := o["List.Open"]; ok { k.List.Open = key.NewBinding(key.WithKeys(v...), key.WithHelp(v[0], "open")) }
    // …
}
```

Help text re-renders automatically because it reads from the same struct.

---

## 9. Concurrency & shutdown

`pubsub.Broker` + long-running goroutines + `tea.Quit` is a panic factory if you don't think about ownership. The contract below makes shutdown deterministic and crash-free.

### 9.1 Bubble Tea v2 shutdown facts (verified)

From `bubbletea/v2.0.6/tea.go`:
- `Run()` blocks on `eventLoop`; on `QuitMsg` (line 765), the loop returns, `Run` renders the final frame, then calls `shutdown(killed)` (line 1241).
- `shutdown` is wrapped in `sync.Once` — idempotent. Order: cancel program ctx → wait for internal goroutines → close cancelReader → stop renderer → restore terminal.
- `Quit()` (line 1197) just sends `QuitMsg{}` via `Send`. `Send` (line 1183) is a no-op once `ctx.Done()` is closed.
- `Kill()` (line 1204) calls `shutdown(true)` directly. `Wait()` (line 1209) blocks on `<-p.finished`.
- **Pending cmds are leaked.** `handleCommands` (line 700) fires each cmd in its own goroutine without tracking it. After shutdown, those goroutines calling `p.Send(msg)` are safe (Send drops on ctx-done), but if they push to *external* channels (your broker), they'll send to closed channels.

### 9.2 Lifecycle contract

```
Phase 0 — boot
    f := log.Setup(...); defer f.Close()
    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()
    a := app.New(ctx, cfg)         // owns broker + service goroutines (selecting on ctx)
    defer a.Shutdown()              // step 4 below

Phase 1 — UI
    p := tea.NewProgram(ui.New(a.Workspace()), …)
    a.AttachProgram(p)             // optional: app pushes broker events into p.Send

    if _, err := p.Run(); err != nil { … }

Phase 2 — orderly teardown (from defers, in reverse order)
    1. cancel()                    // signals every goroutine that selects on ctx.Done()
    2. a.Shutdown()                // waits for service WG, THEN closes broker
    3. f.Close()                   // log file
```

Inside `app.Shutdown()`:

```go
func (a *App) Shutdown() {
    a.cancel()             // belt-and-suspenders; cancel goroutines
    a.serviceWG.Wait()     // wait for every publisher goroutine to return
    a.events.Close()       // safe NOW: no publishers remain
}
```

### 9.3 Publisher rules (mandatory)

Every goroutine that publishes to the broker:

```go
func (s *Service) run(ctx context.Context, b *pubsub.Broker[tea.Msg]) {
    defer s.wg.Done()
    for {
        select {
        case <-ctx.Done():
            return                          // don't publish after this
        case ev := <-s.in:
            select {
            case b.publishCh <- ev:         // (or use b.Publish(ev) if non-blocking)
            case <-ctx.Done():
                return
            }
        }
    }
}
```

The `Broker.Publish` shown in §4.3 is non-blocking and acquires `RLock`; it checks `<-b.done` and drops silently if closed. Combined with the publisher rule above, a send-on-closed-channel is impossible.

### 9.4 Subscriber side

Subscribers receive a channel that **closes when ctx cancels OR when the broker closes**. The UI's `waitForEvent` (§4.3) detects close via the `, ok` idiom and emits a `shutdownMsg`, letting the root Update finalize gracefully (e.g. flush a draft). Range-loop subscribers exit naturally when the channel closes.

### 9.5 Things that go wrong if you skip the contract

| Bug | Symptom | Cause |
|---|---|---|
| Send-on-closed-channel panic | Process crashes during exit | Publisher kept running after broker close |
| Hung shutdown | Process doesn't exit | Subscriber blocks Publish; missing buffered channel or `default:` drop |
| Lost final events | UI misses last update | UI's `waitForEvent` exits on close before consuming buffered messages — drain in Update before returning |
| Wedged terminal | `cat` no longer echoes after panic | No `recover` + `RestoreTerminal` at top level (§6.4) |

### 9.6 Testing the broker

Race-test `Subscribe`/`Publish`/`Close` under `-race`. The asserts that matter:
- Publish after Close is a no-op (no panic).
- Subscribe after Close returns a closed channel.
- Close is idempotent.
- Subscribers unblock when their `ctx` cancels even if the broker stays open.

---

## 10. Theming & styles

Centralize *everything* visual in `internal/ui/styles/`. Three files:

```
internal/ui/styles/
├── palette.go     # semantic tokens: BgPrimary, FgMuted, AccentPrimary, Danger, …
├── styles.go      # nested Styles struct: Header, Footer, Page, List, Dialog, … 
└── themes.go      # constructor functions: Dark() *Styles, Light() *Styles, HighContrast() *Styles
```

`Styles` is a value type passed via `*common.Common` to every component. Components never construct lipgloss styles inline — they read from `c.Styles.Foo.Bar`. This is what lets you re-theme the entire app with one constructor swap.

```go
// styles/palette.go
type Palette struct {
    Bg, BgAlt, Fg, FgMuted color.Color
    Accent, Danger, Warn   color.Color
}

// styles/styles.go
type Styles struct {
    Header struct{ Title, Tab, ActiveTab lipgloss.Style }
    List   struct{ Item, Selected, Disabled lipgloss.Style }
    Dialog struct{ Border, Title, Body lipgloss.Style }
    // …
}
func From(p Palette) *Styles { /* one place that mints all the lipgloss.Style values */ }

// styles/themes.go
func Dark() *Styles  { return From(Palette{Bg: lipgloss.Color("#0b0b0f"), …}) }
func Light() *Styles { return From(Palette{Bg: lipgloss.Color("#fdfdfd"), …}) }
```

**Adapt to terminal background**: in `Init`, request the background color (`tea.RequestBackgroundColor`); in `Update`, swap themes on `tea.BackgroundColorMsg`. This is the only correct way under SSH.

---

## 11. Testing strategy

Test pyramid, sharpest at the top:

```
            ┌────────────────────────────┐
            │  e2e teatest/v2 (5–10)     │  full program flows; few, slow
            ├────────────────────────────┤
            │  Golden View snapshots     │  one per page+state; visual regression
            ├────────────────────────────┤
            │  tea.Cmd factory tests     │  each cmd → expected msg
            ├────────────────────────────┤
            │  Update unit tests (many)  │  table-driven; fast; the bulk of coverage
            └────────────────────────────┘
```

### 11.1 Update unit tests — the workhorse

```go
// internal/ui/pages/list/page_test.go
func TestUpdate(t *testing.T) {
    t.Parallel()
    tests := []struct {
        name    string
        in      Model
        msg     tea.Msg
        want    Model
        wantCmd bool
    }{
        {"down moves cursor",   newModel(),
            tea.KeyPressMsg{Code: tea.KeyDown}, modelAt(1), false},
        {"q quits page",        newModel(),
            tea.KeyPressMsg{Code: 'q', Text: "q"}, newModel().withQuit(), true},
        {"resize updates size", newModel(),
            tea.WindowSizeMsg{Width: 80, Height: 24}, modelSize(80, 24), false},
    }
    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            t.Parallel()
            got, cmd := tt.in.Update(tt.msg)
            require.Equal(t, tt.want, got)
            require.Equal(t, tt.wantCmd, cmd != nil)
        })
    }
}
```

A `tea.Cmd` is just `func() tea.Msg` — when you need to assert *what* a cmd produces, call it: `msg := cmd()`.

### 11.2 Command factory tests

```go
func TestFetchUsersCmd(t *testing.T) {
    fake := &fakeUserSvc{users: []User{{Name: "alice"}}}
    cmd := fetchUsers(fake)
    msg := cmd().(usersLoadedMsg)
    require.Equal(t, "alice", msg.users[0].Name)
}
```

### 11.3 Golden snapshots — one per stable state, with a containment rule

`github.com/charmbracelet/x/exp/golden` is the standard. It writes ANSI-quoted text to `testdata/<TestName>.golden`, runs unified diff on compare, regenerates with `go test -update`.

```go
func TestPage_Welcome_Render(t *testing.T) {
    m := New(testWorkspace(t)).Resize(80, 24)
    golden.RequireEqual(t, []byte(m.View().String()))
}
```

**Containment rule** (the difference between a sustainable golden suite and a sprawl):

- **Where**: golden snapshots ONLY at *page-level* major states. Components below page level get no golden tests.
- **What states**: `landing`, `empty`, `loading`, `error`, `loaded` per page. Five baseline states is enough for "does it render"; add a state only after a regression has been observed.
- **Variants**: one variant per theme (`dark`, `light`) and one extra "narrow" width if responsive. NOT one per row, NOT one per data permutation.
- **Component tests**: assert via `Update(msg)` returning expected `Model` state (focus, cursor, selected index). Never on rendered string.
- **Budget**: total `testdata/` size ≤ **1 MB**. Enforce with a one-line CI gate:
  ```sh
  test "$(du -sk testdata 2>/dev/null | cut -f1)" -le 1024 || { echo "testdata > 1MB"; exit 1; }
  ```

For reference: Crush's `internal/ui/diffview/testdata` has 363 `.golden` files at ~1.77 MB — useful for a diff-rendering crown jewel, but if every component went that wide, the suite would be unmanageable. Crush's choice is to be lavish *only* in the package whose visual output is the product itself; everywhere else, they unit-test `Update`.

### 11.4 End-to-end with `teatest/v2`

For top user flows only — login → list → open detail → quit. They run a real `tea.Program` against in-memory pipes.

```go
func TestFlow_OpenDetail(t *testing.T) {
    tm := teatest.NewTestModel(t, ui.New(testWorkspace(t)),
        teatest.WithInitialTermSize(100, 30),
        teatest.WithProgramOptions(tea.WithColorProfile(colorprofile.Ascii)))
    t.Cleanup(func() { _ = tm.Quit() })

    tm.Send(tea.KeyPressMsg{Code: tea.KeyDown})
    tm.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
    teatest.WaitFor(t, tm.Output(), func(b []byte) bool {
        return bytes.Contains(b, []byte("DETAIL"))
    }, teatest.WithDuration(2*time.Second))

    out, _ := io.ReadAll(tm.FinalOutput(t, teatest.WithFinalTimeout(time.Second)))
    teatest.RequireEqualOutput(t, out)   // golden compare
}
```

### 11.5 Mocking dependencies

Hand-rolled fakes win. Small interfaces, constructor injection:

```go
type fakeUserSvc struct {
    users []User
    err   error
    calls atomic.Int64
}
func (f *fakeUserSvc) List(ctx context.Context) ([]User, error) {
    f.calls.Add(1)
    return f.users, f.err
}
```

Use `gomock`/`testify mock` only when interfaces are large or call ordering matters. For a TUI, hand-rolled fakes cover 90%+ of cases in 5 lines each.

### 11.6 Test harness package

`internal/testutil/teaharness.go` centralizes the boilerplate so every test isn't a 10-line setup:

```go
func NewHarness(t *testing.T, m tea.Model, opts ...Opt) *teatest.TestModel
func RequireRendered(t *testing.T, h *teatest.TestModel, contains string, within time.Duration)
func MustQuit(t *testing.T, h *teatest.TestModel)
func TestWorkspace(t *testing.T, opts ...WSOpt) workspace.Workspace
```

### 11.7 Coverage targets and Codecov reporting

**Targets** (component-scoped, not a single repo number):

| Target | Aim | Enforced |
|---|---|---|
| `internal/feature/**` | ≥ 90% | Codecov component gate |
| `internal/ui/pages/**` (Update + cmd factories only) | ≥ 85% | Codecov component gate |
| `internal/ui/components/**` | ≥ 80% | Codecov component gate |
| `internal/pubsub/**` | ≥ 90% | Codecov component gate |
| `internal/ui/**/View` (rendering) | golden coverage on key states | not line-coverage gated |
| End-to-end via `teatest/v2` | 5–10 flows | counted, not gated |

Don't chase line coverage in `View()` — it's pure rendering and fragile against style tweaks. Catch regressions with golden files at meaningful boundaries.

**Emission — one file, two formats:**

```sh
# Native Go format (atomic mode is required for -race)
go test -race -covermode=atomic -coverpkg=./internal/... -coverprofile=coverage.out ./...

# Convert to Codecov-friendly Cobertura XML for monorepo parity with JaCoCo
go install github.com/boumenot/gocover-cobertura@latest
gocover-cobertura < coverage.out > coverage.xml
```

- `-coverpkg=./internal/...` is critical: without it, package-internal tests only count code in their own package. With it, tests in `internal/ui/pages/list/` measure coverage of `internal/feature/...` they exercise via Workspace.
- `-covermode=atomic` is required when you also use `-race`. Don't switch to `count` to silence a warning — silently dropping data is worse than a wordy CI log.
- `coverage.out` is Go's native text format (kept for `go tool cover -html=coverage.out` local browsing). `coverage.xml` is Cobertura, which Codecov parses uniformly with the JaCoCo reports the Java modules emit. **Both go to Codecov** so PR diffs work even if a future tool only understands one format.

**Codecov configuration** — `codecov.yml` at repo root (Java + Go monorepo example):

```yaml
codecov:
  require_ci_to_pass: true

coverage:
  precision: 1
  round: down
  status:
    project:
      default:
        target: auto
        threshold: 0.5%        # tolerate noise
    patch:
      default:
        target: 80%            # new code must be ≥ 80% covered
        threshold: 0%

# Per-component gates (the table above)
component_management:
  individual_components:
    - component_id: tui-feature
      name: tui/internal/feature
      paths: [tui/internal/feature/**]
      statuses:
        - type: project
          target: 90%
    - component_id: tui-pages
      name: tui/internal/ui/pages
      paths: [tui/internal/ui/pages/**]
      statuses:
        - type: project
          target: 85%
    - component_id: tui-components
      name: tui/internal/ui/components
      paths: [tui/internal/ui/components/**]
      statuses:
        - type: project
          target: 80%
    - component_id: tui-pubsub
      name: tui/internal/pubsub
      paths: [tui/internal/pubsub/**]
      statuses:
        - type: project
          target: 90%
    # Java components stay alongside; declare them here too
    # - component_id: app, paths: [app/src/main/**], statuses: [...]

flags:
  go:
    paths: [tui/]
    carryforward: true         # if a PR doesn't touch Go, last green Go report counts
  java:
    paths: [app/, lib/]
    carryforward: true

ignore:
  - "tui/cmd/**"               # main.go is wiring; not worth gating
  - "tui/internal/ui/**/View"  # rendering — golden tests cover these
  - "**/testdata/**"
  - "**/*_test.go"
  - "**/mocks/**"

comment:
  layout: "diff,flags,components,files"
  require_changes: true
```

**Two flags, two uploads.** The Go and Java reports are uploaded with separate flags so Codecov computes coverage per-language *and* aggregates. `carryforward` means a Java-only PR doesn't show a fake "Go coverage dropped to 0%" because the Go suite didn't run.

**Codecov upload step** uses the v4 action (token-based, supports Cobertura natively):

```yaml
- uses: codecov/codecov-action@v4
  with:
    token: ${{ secrets.CODECOV_TOKEN }}
    files: tui/build/coverage/coverage.xml      # Cobertura XML emitted by goCoverageXml task
    flags: go
    fail_ci_if_error: true
```

Java modules upload `app/build/reports/jacoco/test/jacocoTestReport.xml` with `flags: java`. Codecov merges them server-side under the `component_management` rules above.

### 11.8 Coverage exclusions in the source

Use Go's standard build-tag pattern for files that genuinely shouldn't be measured (generated code, vendor compatibility shims):

```go
//go:build !coverage
// +build !coverage
```

Sparingly. Most exclusions belong in `codecov.yml`'s `ignore:` list, not in the source — keeps the source clean and the policy auditable in one place.

### 11.9 CI determinism

`internal/testutil/init.go`:

```go
func init() {
    os.Setenv("TERM", "xterm-256color")
    // teatest disables signals and isolates I/O; force ASCII for stable goldens:
    // pass tea.WithColorProfile(colorprofile.Ascii) into teatest.WithProgramOptions
}
```

`.gitattributes`:

```
testdata/** -text       # don't munge line endings on Windows
*.golden    -text
```

Don't allocate a TTY; teatest pipes I/O in-memory. Run `go test ./...` plain.

---

## 12. Tooling & quality gates

Required toolchain (pinned in `mise.toml` / `asdf` config):
- `go 1.25.0+`
- `golangci-lint` ≥ v1.62 (needed for `forbidigo.analyze-types` and v2 ecosystem support)
- `gotestsum` (test runner with nicer output)
- `gofumpt` and `goimports` (run via golangci-lint or pre-commit)
- `lefthook` (single-binary git-hook manager) — optional but recommended

### 12.1 Lint policy — every linter backs a rule from this plan

The lint suite isn't a "trendy linters" list — it mechanically enforces the rules established elsewhere in this doc. If a linter doesn't trace to a section, it doesn't ship.

| Group | Linters | Backs |
|---|---|---|
| Correctness | `errcheck` `govet` `staticcheck` `gosimple` `ineffassign` `typecheck` `unused` | — (table stakes) |
| Concurrency & context | `contextcheck` `containedctx` `noctx` `bodyclose` | §9 contract; Model MUST NOT carry ctx; cmds MUST select on ctx.Done |
| Error hygiene | `errorlint` `nilerr` | §7 — proper `%w` wrapping for `ErrorMsg{Err}` |
| Imports & modules | `depguard` `importas` `gomoddirectives` | §1 ("no v1↔v2 drift"); §3 ("ui does no I/O") |
| Forbidden patterns | `forbidigo` | §6 (no `fmt.Print*`); §5 (no `os.Getenv` in ui) |
| Format | `gofumpt` `goimports` `misspell` `unconvert` `whitespace` | — |
| Test quality | `thelper` `tparallel` `paralleltest` `testifylint` | §11 — tests use `t.Parallel` + testify |
| Style hygiene | `revive` (light) `nolintlint` `predeclared` `unparam` `wastedassign` | — |

**Deliberately disabled** (with reasons): `gomnd`/`funlen` (layout math has real magic numbers), `lll` (breaks tables), `exhaustruct` (`tea.View`/lipgloss zero-values are intentional), `gochecknoglobals` (theme styles are package vars), `varnamelen` (TUI uses short conventional names: `m`, `c`, `w`, `h`), `wrapcheck` (too noisy for command factories that wrap once at the boundary).

### 12.2 The plan-specific bits of `.golangci.yml`

The boilerplate (`run.timeout`, `output.format`, `linters.disable-all`, `linters.enable: [...the list above...]`) is omitted. The non-obvious settings — the ones that *enforce* this plan — are:

```yaml
linters-settings:
  goimports:
    local-prefixes: example.com/tui     # group your-module imports below stdlib

  importas:
    no-unaliased: true                  # force consistent alias for v2 packages
    alias:
      - pkg: charm.land/bubbletea/v2
        alias: tea
      - pkg: charm.land/bubbles/v2/key
        alias: key
      - pkg: charm.land/bubbles/v2/help
        alias: help
      - pkg: charm.land/lipgloss/v2
        alias: lipgloss
      - pkg: github.com/charmbracelet/x/ansi
        alias: ansi
      - pkg: github.com/charmbracelet/x/exp/teatest/v2
        alias: teatest
      - pkg: github.com/charmbracelet/x/exp/golden
        alias: golden

  depguard:
    rules:
      # Hard ban on v1 charm imports — mechanically enforces §1 goal.
      no-charm-v1:
        deny:
          - pkg: github.com/charmbracelet/bubbletea
            desc: "Use charm.land/bubbletea/v2 (PLAN §2)."
          - pkg: github.com/charmbracelet/bubbles
            desc: "Use charm.land/bubbles/v2 (PLAN §2)."
          - pkg: github.com/charmbracelet/lipgloss
            desc: "Use charm.land/lipgloss/v2 (PLAN §2)."
      # internal/ui does no I/O — enforces §3 rule 1.
      tui-no-io:
        files: ['**/internal/ui/**']
        deny:
          - pkg: database/sql
            desc: "internal/ui does no I/O; go through Workspace (PLAN §3)."
          - pkg: net/http
            desc: "internal/ui does no I/O; go through Workspace (PLAN §3)."
          - pkg: os/exec
            desc: "internal/ui does no I/O; go through Workspace (PLAN §3)."

  forbidigo:
    analyze-types: true                 # match the qualified function name
    forbid:
      # §6 — fmt.Print* / print / println corrupt the alt-screen
      - p: '^fmt\.(Print|Println|Printf)$'
        msg: "Use slog; fmt.Print* corrupts the TUI screen (PLAN §6.3)."
      - p: '^print$'
        msg: "Use slog; print() corrupts the TUI screen."
      - p: '^println$'
        msg: "Use slog; println() corrupts the TUI screen."
      # §5 — env should arrive via tea.EnvMsg under SSH/Wish
      - p: '^os\.Getenv$'
        msg: "Read env from tea.EnvMsg — os.Getenv is wrong under SSH (PLAN §5)."
      - p: '^os\.Environ$'
        msg: "Read env from tea.EnvMsg — os.Environ is wrong under SSH (PLAN §5)."

  testifylint:
    enable-all: true

  revive:
    severity: warning
    rules:
      - { name: blank-imports }
      - { name: context-as-argument }
      - { name: context-keys-type }
      - { name: dot-imports }
      - { name: error-naming }
      - { name: error-return }
      - { name: error-strings }
      - { name: exported }
      - { name: indent-error-flow }
      - { name: receiver-naming }
      - { name: time-naming }
      - { name: unused-parameter }
      - { name: var-naming }

issues:
  exclude-rules:
    # cmd/tui/main.go is the one place pre-Run stdout writes are legitimate
    # (--help, --version, fatal startup errors before the alt-screen exists)
    - path: ^cmd/tui/main\.go$
      linters: [forbidigo]
    # log package writes to stderr in --no-tui mode (§6.2)
    - path: ^internal/log/
      linters: [forbidigo]
    # tests relax a handful of rules
    - path: _test\.go$
      linters: [unparam, contextcheck, noctx, forbidigo, containedctx]
  max-issues-per-linter: 0
  max-same-issues: 0
```

**Note on `len(s)` vs `lipgloss.Width(s)` (§5):** there's no clean golangci-lint rule that forbids `len()` on *strings* without false-positiving on slices. Catch this in code review; document the rule in `internal/ui/AGENTS.md`. A custom `golang.org/x/tools/go/analysis` analyzer could enforce it, but only worth building after a regression has actually happened.

**Note on `unused` and the `Workspace` interface:** when iterating, you'll have placeholder methods that aren't called yet. Use `//nolint:unused // placeholder for §5 phase` with `nolintlint` enforcing that every nolint has a comment.

### 12.3 Pre-commit / pre-push hooks

`lefthook` (single Go binary, fast, no Python) keeps lint/format from being CI-only:

```yaml
# lefthook.yml
pre-commit:
  parallel: true
  commands:
    fmt:
      glob: "*.go"
      run: gofumpt -l -w {staged_files} && goimports -local example.com/tui -w {staged_files}
      stage_fixed: true
    vet:
      run: go vet ./...

pre-push:
  parallel: false
  commands:
    lint:
      run: golangci-lint run ./...
    test:
      run: go test -race -timeout 2m ./...
    testdata-budget:
      run: make testdata-budget
```

Install: `lefthook install`. Skipping a hook in an emergency: `LEFTHOOK=0 git push` — `nolintlint` and CI will catch you.

### 12.4 Suggested `Makefile`

```make
.PHONY: build test test-update lint fmt cover cover-xml run testdata-budget

BIN_DIR ?= build/bin
COV_DIR ?= build/coverage

build:
	@mkdir -p $(BIN_DIR)
	go build -o $(BIN_DIR)/tui ./cmd/tui

test:
	gotestsum --format pkgname -- -race -timeout 2m ./...

test-update:                 # regenerate golden files
	go test ./... -update

lint:
	golangci-lint run ./...

fmt:
	gofumpt -l -w .
	goimports -local example.com/tui -w .

# Coverage: native Go profile (for `go tool cover -html`) AND Cobertura XML (for Codecov)
cover:
	@mkdir -p $(COV_DIR)
	go test -race -covermode=atomic -coverpkg=./internal/... \
	    -coverprofile=$(COV_DIR)/coverage.out ./...
	go tool cover -func=$(COV_DIR)/coverage.out | tail -1

cover-xml: cover
	@command -v gocover-cobertura >/dev/null 2>&1 || go install github.com/boumenot/gocover-cobertura@latest
	gocover-cobertura < $(COV_DIR)/coverage.out > $(COV_DIR)/coverage.xml
	@echo "Cobertura XML: $(COV_DIR)/coverage.xml"

testdata-budget:             # enforce the §11.3 cap
	@total=$$(find . -type d -name testdata -exec du -sk {} + 2>/dev/null | awk '{s+=$$1} END{print s+0}'); \
	 if [ "$$total" -gt 1024 ]; then echo "testdata budget: $${total}KB > 1024KB"; exit 1; fi

run:
	go run ./cmd/tui
```

Output paths intentionally mirror Gradle conventions (`build/bin`, `build/coverage`) so the Gradle wrapper in §15 can pick them up without a translation layer.

### 12.5 CI gates

Single workflow, fail-fast:

| Step | Command | Gating |
|---|---|---|
| Build | `make build` (or `./gradlew :tui:assemble`) | required |
| Lint | `make lint` | required |
| Vet (extras) | `go vet ./...` | required (catches a couple things golangci-lint's vet preset misses) |
| Test | `make test` | required |
| Goldens up-to-date | `git diff --exit-code testdata` after `go test ./... -update` | required |
| Testdata budget | `make testdata-budget` | required |
| Coverage emit | `make cover-xml` | required (must produce `build/coverage/coverage.xml`) |
| Codecov upload | `codecov/codecov-action@v4` with `flags: go` | required (best-effort: `fail_ci_if_error: false` while baseline is established, then flip to `true`) |
| Codecov gate | server-side via §11.7 `codecov.yml` | gating once baselines exist |

---

## 13. Documentation

Three documents, kept in-repo:

1. **`README.md`** — quickstart, install, screenshot, key bindings table.
2. **`internal/ui/AGENTS.md`** — the architecture note that describes the layering, model-composition strategy, and any deviations. Keep it short; 1–2 pages. Crush ships one and it's the single most useful internal doc in that codebase.
3. **`CHANGELOG.md`** — `Keep a Changelog` format; cut a tag when CI is green.

---

## 14. Phased roadmap

Each phase is a working, demo-able cut of the app. Don't skip phases — the test infrastructure (Phase 2) and concurrency contract (Phase 4) compound heavily once they're in place.

### Phase 0 — Scaffold (½ day)
- `go.mod` with v2 pins; `cmd/tui/main.go` printing `"hello"`.
- Empty `internal/{app,ui,pubsub,feature,config,log,testutil}` with one stub each.
- `Makefile`, `.golangci.yml` (with §6.3 forbidigo rule), `.gitattributes`, GitHub Actions workflow.
- `internal/log/log.go` with `Setup(verbose)` per §6.1; `cmd/tui/main.go` calls it before any other code.
- Top-level `recover` + `RestoreTerminal` per §6.4.
- Smoke test: `go build` and `go test ./...` both pass.

### Phase 1 — First vertical slice (1–2 days)
- `internal/ui/ui.go` root model with WindowSize + Quit handling.
- One page (`internal/ui/pages/landing`) returning a static view.
- `internal/ui/styles/{palette,styles,themes}.go` with `Dark()` only.
- `internal/ui/workspace/workspace.go` interface + a `fakeWorkspace` in tests.
- `internal/ui/keys/keys.go` with `KeyMap` struct + `Default()` per §8.1; root model wires `help.Model`.
- 10+ table-driven `Update` tests on the root model and the page.
- `cmd/tui` wires a real `App` with a no-op service implementation; running the binary shows the landing page in alt-screen.

### Phase 2 — Testing infrastructure (1 day)
- `internal/testutil/teaharness.go` (NewHarness, TestWorkspace, RequireRendered).
- First golden snapshot for the landing page (1 file, baseline).
- First teatest e2e: launch → quit, with golden output check.
- `make test-update` regenerates goldens; CI gate ensures goldens are committed AND `make testdata-budget` is enforced (§11.3).
- `make cover` and `make cover-xml` produce `build/coverage/coverage.{out,xml}`; CI uploads `coverage.xml` to Codecov with `flags: go` (§11.7). Per-component thresholds in `codecov.yml` start informational, flip to gating once a baseline run exists.

### Phase 3 — Theming, dialogs, and error UX (1–2 days)
- `Light()` and `HighContrast()` themes; verify golden files for each via subtests (5 states × 2 themes = 10 goldens, well within budget).
- `internal/ui/dialog` package with `Dialog` interface + `Stack`.
- Confirmation dialog component (yes/no), driven by `Action` return type.
- `internal/ui/msg/error.go` with `ErrorMsg{Err, Severity, Title, TTL}` (§7.2); root model routes to toast/status/modal.
- Theme switching reacts to `tea.BackgroundColorMsg`.

### Phase 4 — Async events via pubsub + shutdown contract (1 day)
- `internal/pubsub/broker.go` per §4.3 (drain-safe Generic broker).
- Race tests for `Subscribe`/`Publish`/`Close` per §9.6.
- Workspace exposes `Events() <-chan tea.Msg`; root model subscribes and re-issues `waitForEvent` per §4.3 with `, ok` close detection.
- `app.Shutdown()` implements the §9.2 lifecycle (cancel → wg.Wait → broker.Close).
- A reference publisher service following §9.3 publisher rules; integration test that asserts no panic on Quit while publisher is mid-publish.

### Phase 5 — Multi-page state machine (when domain is known)
- Domain pages under `internal/ui/pages/<page>` per feature.
- Navigation messages (`msg.NavigateMsg{To: pageID}`) handled by root.
- Per-page key bindings under `keys.KeyMap.<Page>` (§8.1); `pageMap.ShortHelp/FullHelp` updated per page.

### Phase 6 — Polish
- Configurable key bindings (`keys.KeyMap.Apply(override)`, §8.3).
- `--screenshot` mode (run, capture View, exit) for docs — uses headless logging mode (§6.2).
- Performance pass: profile under realistic data; only then consider the imperative-subcomponent escape hatch from §4.4 if propagation is the bottleneck.

---

## 15. Gradle monorepo integration

This project lives inside a mostly-Java Gradle monorepo. The integration policy is **native Go internally, thin Gradle wrapper on top** — Gradle owns the entry points (`./gradlew build`, CI) but never tries to "speak Go." There is no maintained Gradle Go plugin worth using; `gogradle` is abandoned.

### 15.1 Non-negotiables

1. **Go developers don't need Gradle.** `cd tui && go test ./...` and `make build` work standalone. If someone has to fight Gradle to do Go work, the wrapper is wrong.
2. **`./gradlew :tui:build` and `make build` produce the same artifact** at the same path. Don't break this — drift here causes "works on CI, breaks locally" pain.
3. **Toolchain pinning is single-sourced.** `mise.toml` (or `.tool-versions`) at repo root pins both `go` and `java`. Gradle's `JavaToolchain` doesn't know about Go; cross-language version coordination belongs in a tool that handles both.
4. **No Java-isms in Go layout.** No `src/main/go`. The Go tree stays idiomatic: `cmd/`, `internal/`, `go.mod` at the project root.
5. **Coverage formats meet halfway.** Go emits Cobertura XML (§11.7), Java emits JaCoCo XML; both go to Codecov with separate flags. Don't try to convert between formats in Gradle — let Codecov handle the merge.

### 15.2 Repo layout

```
monorepo/
├── settings.gradle.kts                 # include(":app", ":lib", ":tui", ...)
├── build.gradle.kts                    # repo-wide tasks (Codecov upload aggregator, etc.)
├── gradle/
│   └── libs.versions.toml              # Java deps; Go pinned in mise.toml
├── mise.toml                           # go = "1.25.0", java = "21" — single source of truth
├── codecov.yml                         # §11.7 (covers Go + Java together)
├── .github/
│   └── workflows/ci.yml                # one workflow runs ./gradlew build
├── app/                                # Java
├── lib/                                # Java
└── tui/
    ├── build.gradle.kts                # ← thin wrapper, ~80 lines
    ├── go.mod
    ├── go.sum
    ├── Makefile                        # §12.4 — works standalone
    ├── codecov.yml                     # OPTIONAL: subproject overrides only
    ├── cmd/tui/...
    └── internal/...
```

### 15.3 `tui/build.gradle.kts` — the thin wrapper

```kotlin
import org.gradle.api.tasks.Exec

plugins { base }                          // gives us assemble/build/check/clean lifecycle

// ---- toolchain & paths ------------------------------------------------------
val goCmd      = providers.environmentVariable("GO").orElse("go")
val golangciCmd = providers.environmentVariable("GOLANGCI_LINT").orElse("golangci-lint")

val binDir   = layout.buildDirectory.dir("bin")
val covDir   = layout.buildDirectory.dir("coverage")
val covOut   = covDir.map { it.file("coverage.out") }
val covXml   = covDir.map { it.file("coverage.xml") }

// ---- inputs (shared file tree to keep build cache hits accurate) -----------
val goSources = fileTree(projectDir) {
    include("**/*.go", "go.mod", "go.sum", "**/testdata/**")
    exclude("build/**", "**/.git/**")
}

// ---- build ------------------------------------------------------------------
val goBuild by tasks.registering(Exec::class) {
    group = "build"; description = "go build ./cmd/tui"
    inputs.files(goSources)
    outputs.dir(binDir)                   // enables Gradle build cache
    workingDir = projectDir
    doFirst { binDir.get().asFile.mkdirs() }
    commandLine(goCmd.get(), "build", "-o",
        binDir.get().file("tui").asFile.absolutePath, "./cmd/tui")
}

// ---- test + coverage --------------------------------------------------------
val goTest by tasks.registering(Exec::class) {
    group = "verification"; description = "go test -race ./..."
    inputs.files(goSources)
    outputs.file(layout.buildDirectory.file("go-test.ok"))
    workingDir = projectDir
    commandLine(goCmd.get(), "test", "-race", "-timeout", "2m", "./...")
    doLast { outputs.files.singleFile.writeText("ok") }
}

val goCoverage by tasks.registering(Exec::class) {
    group = "verification"
    description = "go test with coverage profile (atomic mode for -race)"
    inputs.files(goSources)
    outputs.file(covOut)
    workingDir = projectDir
    doFirst { covDir.get().asFile.mkdirs() }
    commandLine(goCmd.get(), "test",
        "-race", "-covermode=atomic", "-coverpkg=./internal/...",
        "-coverprofile=${covOut.get().asFile.absolutePath}",
        "./...")
}

val goCoverageXml by tasks.registering(Exec::class) {
    group = "verification"
    description = "Convert Go coverage profile to Cobertura XML for Codecov"
    dependsOn(goCoverage)
    inputs.file(covOut)
    outputs.file(covXml)
    workingDir = projectDir
    // gocover-cobertura reads stdin and writes stdout; wrap with sh -c
    commandLine("sh", "-c",
        "gocover-cobertura < '${covOut.get().asFile.absolutePath}' > '${covXml.get().asFile.absolutePath}'")
    doFirst {
        // Ensure the converter is installed; it lives in $GOBIN
        exec {
            commandLine(goCmd.get(), "install",
                "github.com/boumenot/gocover-cobertura@latest")
        }
    }
}

// ---- lint -------------------------------------------------------------------
val goLint by tasks.registering(Exec::class) {
    group = "verification"; description = "golangci-lint run"
    inputs.files(goSources)
    inputs.file(rootProject.file(".golangci.yml"))
    outputs.file(layout.buildDirectory.file("go-lint.ok"))
    workingDir = projectDir
    commandLine(golangciCmd.get(), "run", "./...")
    doLast { outputs.files.singleFile.writeText("ok") }
}

// ---- testdata budget (mirrors the Make target) ------------------------------
val goTestdataBudget by tasks.registering {
    group = "verification"; description = "Enforce testdata size cap (§11.3)"
    doLast {
        val total = fileTree(projectDir) {
            include("**/testdata/**")
            exclude("build/**")
        }.sumOf { it.length() }
        val capKb = 1024L
        val totalKb = total / 1024
        if (totalKb > capKb)
            throw GradleException("testdata budget exceeded: ${totalKb}KB > ${capKb}KB")
    }
}

// ---- lifecycle wiring -------------------------------------------------------
tasks.named("assemble") { dependsOn(goBuild) }
tasks.named("check")    { dependsOn(goTest, goLint, goCoverageXml, goTestdataBudget) }
tasks.named("clean")    { doLast { delete(binDir, covDir,
                                          layout.buildDirectory.file("go-test.ok"),
                                          layout.buildDirectory.file("go-lint.ok")) } }

// ---- expose coverage XML to root project for the aggregator upload ---------
configurations.create("coverageReport") {
    isCanBeConsumed = true; isCanBeResolved = false
    attributes {
        attribute(Attribute.of("report.format", String::class.java), "cobertura")
        attribute(Attribute.of("report.language", String::class.java), "go")
    }
}
artifacts {
    add("coverageReport", covXml.get().asFile) {
        builtBy(goCoverageXml)
    }
}
```

What this buys:

- `./gradlew :tui:build` → `goBuild` → `tui/build/bin/tui`. Same path Make uses.
- `./gradlew :tui:check` → lint + race-tests + Cobertura XML + testdata budget. Same checks Make+CI run.
- `inputs/outputs` declarations enable Gradle's build cache: `./gradlew build` is a no-op when nothing under `tui/` changed. Without these, the wrapper is just slower than Make.
- The custom `coverageReport` configuration lets the root build aggregate Go + Java coverage XML into a single Codecov upload step (§15.5).

### 15.4 What Gradle does NOT do

- **No source-set translation.** Go owns its layout; Gradle just shells out.
- **No dependency management.** `go.mod` is authoritative for Go deps. Don't try to mirror them in `libs.versions.toml`.
- **No format conversion in JVM.** `gocover-cobertura` is a Go tool; let Go run it.
- **No Java→Go or Go→Java codegen.** If they need to interop at runtime, do it over a stable wire protocol (gRPC, JSON over stdin/stdout, files). Cross-language codegen in a monorepo grows tentacles.
- **No cleaning of `$GOPATH/pkg/mod` or `$GOCACHE`.** Those are user-global. `./gradlew clean` only wipes `tui/build/`.

### 15.5 Codecov upload from Gradle CI

Single workflow at the repo root:

```yaml
# .github/workflows/ci.yml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: jdx/mise-action@v2          # installs go + java per mise.toml
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew build              # runs Java + Go test + coverage

      # Two uploads, two flags. Codecov merges per codecov.yml (§11.7).
      - uses: codecov/codecov-action@v4
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: tui/build/coverage/coverage.xml
          flags: go
          fail_ci_if_error: false         # flip to true after baseline established
      - uses: codecov/codecov-action@v4
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: app/build/reports/jacoco/test/jacocoTestReport.xml,lib/build/reports/jacoco/test/jacocoTestReport.xml
          flags: java
          fail_ci_if_error: false
```

`./gradlew build` runs both tracks in parallel by default (Java JaCoCo + Go Cobertura). Codecov receives two reports tagged `go` and `java`; the `component_management` rules in `codecov.yml` (§11.7) compute per-component coverage and gate the PR.

### 15.6 Local developer workflow

| You want to… | Run this |
|---|---|
| Build the TUI | `cd tui && make build` (or `./gradlew :tui:build`) |
| Run tests + race | `cd tui && make test` |
| Regenerate goldens | `cd tui && make test-update` |
| Get a local HTML coverage report | `cd tui && make cover && go tool cover -html=build/coverage/coverage.out` |
| Validate the whole monorepo (Java + Go) before pushing | `./gradlew build` from repo root |
| Update Go toolchain version | edit `mise.toml`, run `mise install` |

Both paths must keep working. If a Gradle change breaks the Make path, fix Gradle.

### 15.7 Things that bite if you're not careful

- **`gotestsum` requires a TTY for the pretty formatter.** Use `--format pkgname` in CI, plain `gotestsum` works locally. Either way the JUnit XML output (`--junitfile`) is independent — wire it the same way as JaCoCo if you want test reports in Gradle's HTML test report aggregator.
- **`-coverpkg=./internal/...` matters.** Without it, tests in `internal/ui/pages/list/` only count code in *that* package, not the `internal/feature/...` they exercise via Workspace. You'll see misleadingly low feature coverage and falsely-high page coverage.
- **`-covermode=atomic` is required with `-race`.** Don't switch to `count` to silence a warning; you'll silently lose data.
- **Path differences between Make and Gradle.** Make builds to `build/bin/tui`; Gradle builds to `tui/build/bin/tui` from repo root (relative). Same directory, different cwd. Document it in the project README.
- **Cobertura `<source>` paths.** `gocover-cobertura` writes paths relative to the module root. Codecov's `paths:` filter under `component_management` must match. The example in §11.7 (`tui/internal/feature/**`) assumes Codecov receives the report from the repo root with `tui/` prefix — that's what `codecov-action@v4` does by default.

---

## Appendix A — Reference patterns (one liners)

- **Don't put I/O in `Update`.** Return `tea.Cmd`s.
- **Don't write to stdout/stderr in TUI mode.** A stray `fmt.Println` corrupts the screen. File-only logger (§6); `forbidigo` lint rule.
- **`tea.Batch` = concurrent. `tea.Sequence` = ordered.** No `tea.Sequentially`.
- **Channel cmds re-issue themselves with `, ok` close detection.** After consuming, return the cmd again. On close, emit a `shutdownMsg` (§4.3).
- **Window size flows top-down.** Root receives `tea.WindowSizeMsg`, computes child rectangles, forwards a custom `msg.LayoutMsg{Width,Height}` to children. Don't expect every child to listen to `WindowSizeMsg` directly — keeps testing simpler.
- **Constructors take options, not flags.** `New(opts ...Opt) Model` with `WithFoo(...)` functions; supports zero-value defaults and easier test setup.
- **Errors are tea.Msgs with severity.** One `msg.ErrorMsg{Err, Severity, Title, TTL}` (§7); routed by severity to toast / status / modal. Never panic in `Update`.
- **Keys and help are one struct.** `keys.KeyMap` carries bindings *and* help text via `key.WithHelp` (§8). Help screen consumes the same struct.
- **Layout math uses `lipgloss.Width`.** Never `len(s)`. Truncate with `ansi.Truncate(s, n, "…")`.
- **Publishers select on `ctx.Done()`.** Send-on-closed-channel panics are bugs. Lifecycle: cancel → wg.Wait → broker.Close (§9).
- **Recover at the top, restore terminal first.** `program.RestoreTerminal()` before printing the panic (§6.4).
- **Avoid singletons.** No package-level state in `internal/ui`. The `Workspace` value is the only ambient.
- **Coverage emits two formats.** `coverage.out` for `go tool cover -html` locally; `coverage.xml` (Cobertura) for Codecov. Always `-coverpkg=./internal/...` and `-covermode=atomic` (§11.7).
- **Gradle wraps, doesn't replace.** Native Go internally; `tui/build.gradle.kts` shells out via `Exec` tasks (§15). Make and Gradle paths must stay equivalent.

## Appendix B — v1 → v2 quick reference (only the parts you'll hit)

```go
// Imports
- "github.com/charmbracelet/bubbletea"
+ tea "charm.land/bubbletea/v2"

// Run
- p.Start()
+ p.Run()

// View
- func (m Model) View() string { return s }
+ func (m Model) View() tea.View { return tea.NewView(s) }

// Keys
- case tea.KeyMsg: if msg.Type == tea.KeyEnter { … }
+ case tea.KeyPressMsg: if msg.Code == tea.KeyEnter { … }

// Toggle alt screen / mouse / focus
- tea.NewProgram(m, tea.WithAltScreen(), tea.WithMouseCellMotion())
+ // set on returned tea.View per frame:
+ v.AltScreen = true; v.MouseMode = tea.MouseModeCellMotion

// Lipgloss color
- lipgloss.NewStyle().Foreground(lipgloss.Color("#FF75B7"))
+ lipgloss.NewStyle().Foreground(lipgloss.Color("#FF75B7"))   // same call site, different return type

// Bubbles viewport
- vp := viewport.New(80, 24)
+ vp := viewport.New(viewport.WithWidth(80), viewport.WithHeight(24))
```

---

## What this plan deliberately leaves open

- **Domain & UX**: pages, components, key bindings, copy. Filled in once UX is defined.
- **CLI framework**: `cobra`, `fang/v2`, or stdlib `flag` — all viable; pick when the binary needs subcommands.
- **Logging handler choice**: `slog` (stdlib JSON) is the safe default chosen in §6. Swap to `charm.land/log/v2` if you want pretty output in `--no-tui` mode; the file destination, lifecycle, and lint rule from §6 stand either way.
- **Persistence / clients**: introduced under `internal/feature/*` when needed; the TUI sees them only via the `Workspace` façade.
- **Imperative-subcomponent refactor (Crush-style)**: revisit only if profiling shows the conventional model is the bottleneck; document the deviation in `internal/ui/AGENTS.md` if adopted.
- **Gradle plugin authoring**: §15 uses raw `Exec` tasks. If multiple Go subprojects appear in the monorepo, factor the wrapper into a `buildSrc` convention plugin (`go-project.gradle.kts`) — single subproject doesn't justify the abstraction yet.
