// Package ui is the TUI surface. The root Model is a page state machine
// that subscribes to the broker once in Init, dispatches messages to
// the active page, and handles global key bindings + navigation.
//
// Strict rule (PLAN §3): nothing in this package or its descendants
// performs I/O. All side-effects live behind Workspace, which returns
// tea.Cmd factories. Tests fake Workspace and call Update directly.
package ui

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/help"
	"charm.land/bubbles/v2/key"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	deployfeat "github.com/opensearch-project/opensearch-migrations/tui/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/launch"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/marelease"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/skillkit"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/versioncheck"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/dialog"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/keys"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
	deploypage "github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/deploy"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/handoff"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/installed"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/intent"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/review"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/welcome"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/workspace"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/workdir"
)

// filepathBase wraps filepath.Base so test-substituting is easy.
var filepathBase = filepath.Base

// osReadFile wraps os.ReadFile and returns the body as a string.
func osReadFile(p string) (string, error) {
	b, err := os.ReadFile(p)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// Model is the root TUI model.
type Model struct {
	c *common.Common

	pageID  msg.PageID
	welcome *welcome.Model
	intent  *intent.Model
	wizard  *wizard.Model
	review  *review.Model
	deploy  *deploypage.Model
	handoff *handoff.Model
	installedPage *installed.Model

	mode    welcome.Mode
	captured intent.Captured
	wizState wizard.State
	callerARN string // captured from AWSDetectedMsg for the deploy driver
	workdirPath string // resolved by prepareWorkdirCmd; passed to launch helpers
	skillBundlePath string // populated by FetchArtifacts; used by finalizeInstallSideEffects
	installedAutoShown bool // true once we've routed the user to the installed page; prevents bounce-loops on refresh

	help    help.Model
	dialogs *dialog.Stack

	// Toast / status bar state — minimal v1 surface.
	statusLine string
	toastLine  string

	// Goodbye message printed after Quit (visible in the user's shell).
	goodbye string

	// quitOnce guards against double-quit cmds.
	quitting bool
}

// New constructs the root model.
func New(ws workspace.Workspace) *Model {
	c := common.New(ws)
	m := &Model{
		c:       c,
		pageID:  msg.PageWelcome,
		welcome: welcome.New(c),
		intent:  intent.New(c),
		wizard:  wizard.New(c),
		review:  review.New(c),
		deploy:  deploypage.New(c),
		handoff: handoff.New(c),
		installedPage: installed.New(c),
		help:    help.New(),
		dialogs: dialog.NewStack(),
	}
	return m
}

// Init kicks off detection and broker subscription.
func (m *Model) Init() tea.Cmd {
	return tea.Batch(
		m.detectAWSCmd(),
		m.detectAgentsCmd(),
		m.detectWorkdirCmd(),
		m.detectMAReleaseCmd(),
		m.detectTUIVersionCmd(),
		m.waitForEvent(),
	)
}

// detectTUIVersionCmd checks GitHub for a newer migration-assistant
// TUI release. Per UX.md §0.5 the prompt is informational — the user
// can keep using the current TUI; we never block launch on this.
func (m *Model) detectTUIVersionCmd() tea.Cmd {
	return func() tea.Msg {
		check := versioncheck.New()
		res, _ := check.TUI(context.Background(), m.c.WS.Version())
		return msg.TUIVersionDetectedMsg{
			Local:         res.Local,
			Latest:        res.Latest,
			Delta:         string(res.Delta),
			SkippedByUser: res.SkippedByUser,
		}
	}
}

// stagesFromExports is a thin alias kept so the ui package's call sites
// don't have to import feature.* themselves.
func stagesFromExports(exports []feature.CFNExport, region string) []string {
	return feature.StagesFromExports(exports, region)
}

// finalizeInstallSideEffects performs every post-deploy bookkeeping
// step: marks the workdir as 'installed', writes HANDOFF.md, and
// installs the per-agent skill kit. Errors are logged but do not
// block the handoff — a missing HANDOFF.md is annoying but not fatal.
func (m *Model) finalizeInstallSideEffects() {
	if m.workdirPath == "" {
		return
	}
	region := m.identityRegionOrDefault()
	account := m.identityAccount()
	cluster := "migration-eks-cluster-" + nz(m.wizState.Stage, "dev") + "-" + region
	ns := nz(m.wizState.Namespace, "ma")

	agent := ""
	skillAgent := skillkit.AgentKiro
	if m.mode == welcome.ModeAgent {
		// Resolve which agent CLI is actually on PATH so the skill kit
		// matches what we'll exec into. Falls back to Kiro when nothing
		// is detected (matches the v1 default).
		switch m.c.WS.AgentBin() {
		case "claude", "claude-code":
			agent = "claude-code"
			skillAgent = skillkit.AgentClaudeCode
		default:
			agent = "kiro"
			skillAgent = skillkit.AgentKiro
		}
	}
	if err := launch.MarkInstalled(m.workdirPath, account, region, "3.2.1", m.c.WS.Version(),
		m.wizState.Stage, modeString(m.mode), agent); err != nil {
		m.c.WS.Logger().Warn("workdir.mark_installed_failed", "err", err)
	}

	brief := handoffbrief.Brief{
		MAVersion:   "3.2.1",
		AWSAccount:  account,
		Region:      region,
		EKSCluster:  cluster,
		Namespace:   ns,
		Stage:       m.wizState.Stage,
		ConsoleExec: "kubectl --context=" + cluster + " -n " + ns + " exec -it migration-console-0 -- /bin/bash",
	}
	if err := launch.WriteHandoffBrief(m.workdirPath, brief, m.captured.MigrationGoal); err != nil {
		m.c.WS.Logger().Warn("handoffbrief.write_failed", "err", err)
	}

	// Skill kit — only when an agent is wanted AND we fetched a bundle.
	if m.mode == welcome.ModeAgent && m.skillBundlePath != "" {
		if err := launch.InstallSkillKit(context.Background(), m.workdirPath, skillAgent, m.skillBundlePath); err != nil {
			m.c.WS.Logger().Warn("skillkit.install_failed", "err", err)
		}
	}
}

// modeString turns welcome.Mode into the string form persisted in
// .ma-state.json ("manual" | "agent").
func modeString(mode welcome.Mode) string {
	if mode == welcome.ModeAgent {
		return "agent"
	}
	return "manual"
}

// identityRegionOrDefault is a small accessor used by finalizeInstallSideEffects.
func (m *Model) identityRegionOrDefault() string {
	if m.wizState.Region != "" {
		return m.wizState.Region
	}
	return "us-east-1"
}

// identityAccount returns the AWS account ID derived from CallerARN.
// Returns "" when AWS detection failed.
func (m *Model) identityAccount() string {
	parts := strings.Split(m.callerARN, ":")
	if len(parts) >= 5 {
		return parts[4]
	}
	return ""
}

// nz returns the first non-empty string from the args.
func nz(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}

// detectMAReleaseCmd fetches the latest MA release tag from GitHub so
// the welcome screen can confirm the version with the user before the
// deploy flow tries to fetch matching artifacts.
func (m *Model) detectMAReleaseCmd() tea.Cmd {
	return func() tea.Msg {
		res := marelease.Fetch(context.Background())
		return msg.MAReleaseDetectedMsg{
			LatestTag: res.Tag,
			PinnedTag: marelease.PinnedDefault,
			Err:       res.Err,
		}
	}
}

// prepareWorkdirCmd creates the named-subdir workspace once we know
// account+region. Idempotent. Errors degrade to a warning toast — the
// TUI still continues so the user can edit settings before retrying.
//
// The result is wrapped in workdirReadyMsg so the main Update goroutine
// owns the only write to m.workdirPath (avoids a data race).
func (m *Model) prepareWorkdirCmd(account, region string) tea.Cmd {
	cwd := m.c.WS.Cwd()
	return func() tea.Msg {
		res, err := launch.PrepareWorkdir(cwd, account, region, "3.2.1")
		if err != nil {
			return msg.ErrorMsg{
				Severity: msg.SevStatus,
				Title:    "workdir",
				Err:      err,
				TTL:      30 * time.Second,
			}
		}
		return workdirReadyMsg{Path: res.Path}
	}
}

// workdirReadyMsg is sent by prepareWorkdirCmd when the named-subdir
// has been created and is ready for the install side-effects.
type workdirReadyMsg struct{ Path string }

// detectWorkdirCmd inspects the launch cwd for a prior install marker
// (UX.md §0.4). Emits WorkdirDetectedMsg synchronously — cheap stat.
//
// Also looks for a saved wizard session and emits sessionLoadedMsg if
// one is found, so root can pre-fill the wizard.
func (m *Model) detectWorkdirCmd() tea.Cmd {
	return func() tea.Msg {
		cwd := m.c.WS.Cwd()
		// Without an account/region we can't compute the named subdir;
		// scan the cwd for any opensearch-migration-* dir with a state file.
		entries, err := os.ReadDir(cwd)
		if err != nil {
			return msg.WorkdirDetectedMsg{Err: err}
		}
		for _, e := range entries {
			if !e.IsDir() {
				continue
			}
			if !strings.HasPrefix(e.Name(), "opensearch-migration-") {
				continue
			}
			wd := cwd + "/" + e.Name()
			st, err := workdir.LoadState(wd)
			if err != nil || st == nil {
				continue
			}
			return msg.WorkdirDetectedMsg{
				Workdir: wd,
				Has:     true,
				Stage:   st.Stage,
				Status:  st.Status,
			}
		}
		return msg.WorkdirDetectedMsg{Has: false}
	}
}

// loadWizardSessionCmd reads the saved wizard session (if any) from the
// resolved workdir and emits sessionLoadedMsg. Best-effort: missing or
// corrupt file just emits an empty value.
func (m *Model) loadWizardSessionCmd(wd string) tea.Cmd {
	return func() tea.Msg {
		var sess wizardSession
		ok, _ := launch.LoadWizardSession(wd, &sess)
		return sessionLoadedMsg{State: sess.State, Cur: sess.Cur, Has: ok}
	}
}

// sessionLoadedMsg carries the result of loading a saved wizard session.
type sessionLoadedMsg struct {
	State wizard.State
	Cur   int
	Has   bool
}

// wizardSession is the on-disk shape persisted by SaveExit.
type wizardSession struct {
	State wizard.State `json:"state"`
	Cur   int          `json:"cur"`
}

// Update is the page state machine.
func (m *Model) Update(message tea.Msg) (tea.Model, tea.Cmd) {
	// Dialogs win on key input — top-of-stack handles keypresses first
	// (PLAN §4.6). Async messages (timers, network results, navigation)
	// flow through to the page underneath so the broker pipeline keeps
	// running while the dialog is open.
	if m.dialogs.Len() > 0 {
		if _, isKey := message.(tea.KeyPressMsg); isKey {
			updated, cmd, action := m.dialogs.Update(message)
			m.dialogs = updated
			switch action {
			case dialog.ActionPropagate:
				// fall through to page handling
			default:
				return m, cmd
			}
		}
	}

	switch v := message.(type) {
	case tea.WindowSizeMsg:
		m.c.Width, m.c.Height = v.Width, v.Height
		// Forward as LayoutMsg so child pages have a single signal to
		// listen for (PLAN appendix A).
		return m, func() tea.Msg { return msg.LayoutMsg{Width: v.Width, Height: v.Height} }

	case tea.KeyPressMsg:
		k := m.c.Keys.Global
		switch {
		case key.Matches(v, k.Quit):
			m.quitting = true
			m.goodbye = "Bye. Re-run `migration-assistant` to continue."
			m.c.WS.SetGoodbye(m.goodbye)
			return m, tea.Quit
		case key.Matches(v, k.Help):
			m.help.ShowAll = !m.help.ShowAll
			return m, nil
		}
		// Forward to active page. dispatchPage already handles every page
		// uniformly, including syncing welcome.SelectedMode after key presses.
		return m.dispatchPage(v)

	case msg.NavigateMsg:
		m.pageID = v.To
		// Re-trigger the page's Init when it becomes active so cursor-
		// blink and other one-shot setup cmds run on entry. Pages return
		// nil if they don't need this.
		switch v.To {
		case msg.PageIntent:
			return m, m.intent.Init()
		case msg.PageWizard:
			return m, m.wizard.Init()
		case msg.PageDeploy:
			return m, m.deploy.Init()
		case msg.PageHandoff:
			return m, m.handoff.Init()
		}
		return m, nil

	case welcome.RefreshAWSMsg:
		// Re-run detection. The cmds run async and emit AWSDetectedMsg /
		// AgentsDetectedMsg back into Update.
		return m, tea.Batch(m.detectAWSCmd(), m.detectAgentsCmd())

	case welcome.SwitchProfileMsg:
		// Open a profile-picker dialog. v1 surface: input the AWS_PROFILE
		// name, set the env var, and re-detect. Init cmd from the dialog
		// (e.g. focus blinking) is batched so it actually runs.
		initCmd := m.dialogs.Push(dialog.NewProfilePicker(func(name string) tea.Cmd {
			if name == "" {
				return nil
			}
			return func() tea.Msg {
				os.Setenv("AWS_PROFILE", name)
				return welcome.RefreshAWSMsg{}
			}
		}))
		return m, initCmd

	case intent.CapturedMsg:
		m.captured = v.Result
		return m, nil

	case wizard.CompletedMsg:
		m.wizState = v.State
		return m, func() tea.Msg { return msg.NavigateMsg{To: msg.PageReview} }

	case wizard.SaveExitMsg:
		// Persist the in-flight wizard state to the workdir, then quit
		// so the next launch can resume from where the user left off.
		// If no workdir was resolved yet (AWS detection still pending),
		// surface a status hint instead of silently dropping the action.
		if m.workdirPath == "" {
			return m, func() tea.Msg {
				return msg.StatusMsg{
					Text: "Cannot save: workdir not yet resolved (AWS detection pending). Press [r] to retry detection first.",
					TTL:  6 * time.Second,
				}
			}
		}
		sess := wizardSession{State: v.State, Cur: v.Cur}
		if err := launch.SaveWizardSession(m.workdirPath, sess); err != nil {
			m.c.WS.Logger().Warn("session.save_failed", "err", err)
			return m, func() tea.Msg {
				return msg.StatusMsg{Text: "Save failed: " + err.Error(), TTL: 6 * time.Second}
			}
		}
		m.quitting = true
		m.goodbye = "Session saved to " + m.workdirPath + "/.ma-session.json. Re-run `migration-assistant` to resume."
		m.c.WS.SetGoodbye(m.goodbye)
		return m, tea.Quit

	case wizard.VPCSelectedMsg:
		return m, tea.Batch(
			m.detectSubnetsCmd(v.Region, v.VPCID),
			m.detectVPCEndpointsCmd(v.Region, v.VPCID),
		)

	case review.LaunchMsg:
		// Persist 'in_progress' so an interrupted run can be detected on
		// the next launch via WorkdirDetectedMsg.Status.
		if m.workdirPath != "" {
			account := m.identityAccount()
			region := m.identityRegionOrDefault()
			if err := launch.MarkStarted(m.workdirPath, account, region, "3.2.1",
				m.c.WS.Version(), m.wizState.Stage, modeString(m.mode)); err != nil {
				m.c.WS.Logger().Warn("workdir.mark_started", "err", err)
			}
		}
		return m, tea.Batch(
			func() tea.Msg { return msg.NavigateMsg{To: msg.PageDeploy} },
			m.deploy.Begin(m.wizState),
			m.startRealDeploy(),
		)

	case deployfeat.PhaseEvent:
		// Stream of events from the real deploy driver — forward to the
		// deploy page as PhaseEventMsg.
		return m, func() tea.Msg { return deploypage.PhaseEventMsg{Event: v} }

	case deployStreamMsg:
		// One event off the driver's channel — forward to the deploy page
		// AND re-issue the wait so the next event drains too.
		ev := v.ev
		// Intercept the synthetic skillbundle event used to thread the
		// skill bundle path back to the main goroutine without a race.
		if ev.Phase == "skillbundle" && ev.Status == "ready" {
			m.skillBundlePath = ev.Message
			return m, deployStreamCmd(v.ch)
		}
		return m, tea.Batch(
			func() tea.Msg {
				return deploypage.PhaseEventMsg{Event: deployfeat.PhaseEvent{
					Phase: ev.Phase, Status: ev.Status, Message: ev.Message, At: ev.At,
				}}
			},
			deployStreamCmd(v.ch),
		)

	case deployStreamDoneMsg:
		return m, nil

	case deploypage.CompletedMsg:
		// Persist install marker, write HANDOFF.md, install skill kit —
		// these were dead-package no-ops before. They produce a complete
		// workspace that survives reboot, ready for the agent or kubectl.
		m.finalizeInstallSideEffects()
		return m, func() tea.Msg { return msg.NavigateMsg{To: msg.PageHandoff} }

	case deploypage.BackgroundedMsg:
		// User backgrounded the deploy. Stash a status hint and route
		// back to welcome so they can pick up other tasks.
		return m, tea.Batch(
			func() tea.Msg { return msg.NavigateMsg{To: msg.PageWelcome} },
			func() tea.Msg {
				return msg.StatusMsg{
					Text: "Deploy backgrounded. It keeps running — re-launch the TUI to see status.",
					TTL:  10 * time.Second,
				}
			},
		)

	case deploypage.FailedMsg:
		// Persist 'failed' so the next launch can offer recovery; the
		// deploy page already shows the error inline.
		if m.workdirPath != "" {
			if err := launch.MarkFailed(m.workdirPath); err != nil {
				m.c.WS.Logger().Warn("workdir.mark_failed", "err", err)
			}
		}
		return m, nil

	case handoff.HandoffMsg:
		// Final handoff. Record the pre-exec banner so main.go can print
		// it after the alt-screen tears down; then quit so the deferred
		// syscall.Exec replaces this process.
		m.quitting = true
		m.goodbye = v.Banner
		m.c.WS.SetGoodbye(v.Banner)
		return m, tea.Quit

	case msg.ShutdownMsg:
		m.quitting = true
		return m, tea.Quit

	case msg.ErrorMsg:
		// Minimal v1 routing: log + status line / dialog. Both SevToast
		// and SevStatus honor TTL via the same statusClearMsg path.
		text := render(v)
		switch v.Severity {
		case msg.SevToast:
			m.toastLine = text
			if v.TTL > 0 {
				return m, tea.Tick(v.TTL, func(time.Time) tea.Msg {
					return toastClearMsg{expected: text}
				})
			}
			return m, nil
		case msg.SevStatus:
			m.statusLine = text
			if v.TTL > 0 {
				return m, tea.Tick(v.TTL, func(time.Time) tea.Msg {
					return statusClearMsg{expected: text}
				})
			}
			return m, nil
		case msg.SevModal:
			initCmd := m.dialogs.Push(dialog.NewError(v.Title, fmt.Sprint(v.Err)))
			return m, initCmd
		}

	case toastClearMsg:
		if m.toastLine == v.expected {
			m.toastLine = ""
		}
		return m, nil

	case msg.StatusMsg:
		m.statusLine = v.Text
		if v.TTL > 0 {
			ttl := v.TTL
			expected := v.Text
			return m, tea.Tick(ttl, func(time.Time) tea.Msg {
				return statusClearMsg{expected: expected}
			})
		}
		return m, nil

	case workdirReadyMsg:
		m.workdirPath = v.Path
		// Push the resolved path into the workspace so the handoff exec
		// can chdir into it. Goroutine-safe — SetWorkdir is mutex-guarded.
		m.c.WS.SetWorkdir(v.Path)
		// Try to restore a previously-saved wizard session.
		return m, m.loadWizardSessionCmd(v.Path)

	case sessionLoadedMsg:
		if v.Has {
			m.wizard.LoadState(v.State)
			m.wizard.SetCur(v.Cur)
			m.wizState = v.State
			return m, func() tea.Msg {
				return msg.StatusMsg{
					Text: "Saved session restored. Press [enter] to walk the wizard from where you left off.",
					TTL:  8 * time.Second,
				}
			}
		}
		return m, nil

	case statusClearMsg:
		// Only clear if we still match — otherwise a fresher status set
		// after the TTL was scheduled would be wiped.
		if m.statusLine == v.expected {
			m.statusLine = ""
		}
		return m, nil

	case msg.LayoutMsg:
		// Forward to current page.
		return m.dispatchPage(v)

	case msg.AWSDetectedMsg, msg.AgentsDetectedMsg, msg.ToolsDetectedMsg:
		// AWS detection is consumed by both the welcome page (display) and
		// the wizard (identity prefill). Tee it. Also kick off VPC
		// detection in the background — by the time the user reaches the
		// VPC step, the list will be ready.
		if aws, ok := message.(msg.AWSDetectedMsg); ok && aws.Err == nil {
			m.wizard.SetIdentity(aws.Identity.UserARN)
			m.callerARN = aws.Identity.UserARN
			// Feed existing-MA-stages into the review page so it can block
			// launch when the wizard's stage collides with a prior install.
			m.review.SetExistingStages(stagesFromExports(aws.MAExports, aws.Identity.Region))

			// Existing-install shortcut (UX.md §13.1): when CFN exports show
			// a prior install, route the user to the action picker instead
			// of forcing them through the wizard. Only auto-route once;
			// subsequent refreshes don't bounce the user.
			if len(aws.MAExports) > 0 {
				cluster := clusterFromExports(aws.MAExports)
				stages := stagesFromExports(aws.MAExports, aws.Identity.Region)
				m.installedPage.SetExisting(cluster, aws.Identity.Region, stages)
				if !m.installedAutoShown && m.pageID == msg.PageWelcome {
					m.pageID = msg.PageInstalled
					m.installedAutoShown = true
				}
			}

			if m.c.WS.AWS() != nil {
				return m, tea.Batch(
					dispatchPageCmd(m, message),
					m.detectVPCsCmd(aws.Identity.Region),
					m.prepareWorkdirCmd(aws.Identity.AccountID, aws.Identity.Region),
				)
			}
		}
		// Agents: pick the first installed and push its bin name into App
		// so the handoff page can resolve the right exec target.
		if ag, ok := message.(msg.AgentsDetectedMsg); ok {
			for _, a := range ag.Agents {
				if a.Path != "" {
					m.c.WS.SetAgentBin(agentBin(a.Name))
					break
				}
			}
		}
		return m.dispatchPage(message)

	case installed.ChosenMsg:
		return m, m.handleInstalledChoice(v)

	case installed.BackMsg:
		// User wants to leave the installed-action picker and walk the
		// regular wizard. Don't auto-bounce them back on the next refresh.
		m.installedAutoShown = true
		return m, func() tea.Msg { return msg.NavigateMsg{To: msg.PageWelcome} }

	case msg.VPCsDetectedMsg:
		m.wizard.SetVPCs(v.VPCs)
		return m, nil

	case msg.SubnetsDetectedMsg:
		m.wizard.SetSubnets(v.Subnets)
		return m, nil

	case msg.VPCEndpointsDetectedMsg:
		m.wizard.SetVPCEndpoints(v.Endpoints)
		return m, nil

	case channelEnvelope:
		// One message lifted out of the broker; re-issue the wait cmd
		// (canonical PLAN §4.3 pattern).
		next := m.dispatchAny(v.payload)
		return m, tea.Batch(next, m.waitForEvent())

	case channelClose:
		// Broker closed — emit ShutdownMsg. The next pass will Quit.
		return m, func() tea.Msg { return msg.ShutdownMsg{} }
	}

	// Default: forward to the active page so it can process page-specific
	// messages (e.g. timer ticks).
	return m.dispatchPage(message)
}

// View composes header + active page + dialog/toast/status overlays.
func (m *Model) View() tea.View {
	var b strings.Builder

	switch m.pageID {
	case msg.PageWelcome:
		b.WriteString(m.welcome.View())
	case msg.PageInstalled:
		b.WriteString(m.installedPage.View())
	case msg.PageIntent:
		b.WriteString(m.intent.View())
	case msg.PageWizard:
		b.WriteString(m.wizard.View())
	case msg.PageReview:
		b.WriteString(m.review.View(m.wizState))
	case msg.PageDeploy:
		b.WriteString(m.deploy.View())
	case msg.PageHandoff:
		b.WriteString(m.handoff.View(m.mode, m.captured, m.wizState))
	default:
		b.WriteString(m.c.Styles.Page.Container.Render(
			fmt.Sprintf("(page %s — not implemented in this build)\n\nPress q to quit.", m.pageID)))
	}

	// Help overlay — toggled by [?]. Renders below the page so users see
	// page context AND the keymap.
	if m.help.ShowAll {
		entries := m.c.Keys.PageHelp(m.pageID.String())
		body := keys.FormatHelp(entries)
		title := m.c.Styles.Form.Label.Render("Keys (current page):")
		b.WriteString("\n\n" + m.c.Styles.Page.Section.Render(title+"\n"+body))
	}

	// Status line (one line, footer area).
	if m.statusLine != "" {
		b.WriteString("\n")
		b.WriteString(m.c.Styles.Status.Warn.Render(m.statusLine))
	}
	if m.toastLine != "" {
		b.WriteString("\n")
		b.WriteString(m.c.Styles.Status.Info.Render(m.toastLine))
	}

	// Dialog overlay
	rendered := b.String()
	if m.dialogs.Len() > 0 {
		rendered = m.dialogs.View(m.c.Width, m.c.Height) + "\n" + rendered
	}

	if m.quitting && m.goodbye != "" {
		// Goodbye is shown as a final post-alt-screen line by main.go;
		// do not render it here (the alt-screen swallows it).
		_ = m.goodbye
	}

	v := tea.NewView(rendered)
	v.AltScreen = true
	v.WindowTitle = "Migration Assistant"
	return v
}

// ---- helpers --------------------------------------------------------------

// dispatchPage routes message to the active page model and returns a
// (root, cmd) pair. Single dispatch table — used both for key presses
// and async messages, so adding a new page only updates one switch.
func (m *Model) dispatchPage(message tea.Msg) (tea.Model, tea.Cmd) {
	switch m.pageID {
	case msg.PageWelcome:
		updated, cmd := m.welcome.Update(message)
		m.welcome = updated
		// Mode is determined by the welcome cursor; capture on every
		// update so any later page can read it via m.mode.
		m.mode = m.welcome.SelectedMode()
		return m, cmd
	case msg.PageInstalled:
		updated, cmd := m.installedPage.Update(message)
		m.installedPage = updated
		return m, cmd
	case msg.PageIntent:
		updated, cmd := m.intent.Update(message)
		m.intent = updated
		return m, cmd
	case msg.PageWizard:
		updated, cmd := m.wizard.Update(message)
		m.wizard = updated
		return m, cmd
	case msg.PageReview:
		updated, cmd := m.review.Update(message)
		m.review = updated
		return m, cmd
	case msg.PageDeploy:
		updated, cmd := m.deploy.Update(message)
		m.deploy = updated
		return m, cmd
	case msg.PageHandoff:
		updated, cmd := m.handoff.Update(message, m.mode, m.captured, m.wizState)
		m.handoff = updated
		return m, cmd
	}
	return m, nil
}

func (m *Model) dispatchAny(payload tea.Msg) tea.Cmd {
	// Synthesize an Update against ourselves, but inline so we don't
	// recurse into channelEnvelope. We just route the payload through
	// the page-dispatch path.
	_, cmd := m.dispatchPage(payload)
	return cmd
}

func render(e msg.ErrorMsg) string {
	if e.Title != "" {
		return e.Title
	}
	if e.Err != nil {
		return e.Err.Error()
	}
	return "(unknown error)"
}

// ---- broker plumbing ------------------------------------------------------

// channelEnvelope wraps a message lifted out of the workspace event
// channel. We use a wrapper so Update can re-issue waitForEvent without
// guessing whether a given msg came from the broker.
type channelEnvelope struct{ payload tea.Msg }
type channelClose struct{}

// statusClearMsg fires after a StatusMsg's TTL elapses.
type statusClearMsg struct{ expected string }

// toastClearMsg fires after a SevToast ErrorMsg's TTL elapses.
type toastClearMsg struct{ expected string }

func (m *Model) waitForEvent() tea.Cmd {
	ch := m.c.WS.Events()
	return func() tea.Msg {
		v, ok := <-ch
		if !ok {
			return channelClose{}
		}
		return channelEnvelope{payload: v}
	}
}

// detectAWSCmd schedules launch-time AWS detection. Synchronous failures
// (no AWS service wired yet) are converted to a friendly Status entry.
func (m *Model) detectAWSCmd() tea.Cmd {
	svc := m.c.WS.AWS()
	if svc == nil {
		return func() tea.Msg {
			return msg.AWSDetectedMsg{Err: errAWSUnavailable}
		}
	}
	return func() tea.Msg {
		ctx := context.Background()
		id, err := svc.WhoAmI(ctx)
		if err != nil {
			return msg.AWSDetectedMsg{Err: err}
		}
		exports, _ := svc.ListMAExports(ctx, id.Region) // soft-fail
		return msg.AWSDetectedMsg{Identity: id, MAExports: exports}
	}
}

// startRealDeploy spawns the real feature/deploy.Driver via the
// workspace façade and pipes its PhaseEvents into Update as
// PhaseEventMsg.
//
// Fetches CFN template + helm chart from the GitHub release first
// (UX.md §0.7) into the named-subdir's artifacts/ folder. If any
// download fails, surfaces a clear red failure on the deploy page —
// silent spinners are never acceptable.
func (m *Model) startRealDeploy() tea.Cmd {
	drv := m.c.WS.DeployDriver()
	if drv == nil {
		return func() tea.Msg {
			return deploypage.PhaseEventMsg{Event: deployfeat.PhaseEvent{
				Phase: "cfn", Status: "failed",
				Message: "AWS not configured — deploy aborted. Run `aws sso login` or set AWS_PROFILE, then re-launch the TUI.",
			}}
		}
	}
	params := buildDeployParams(m.wizState, m.callerARN)
	// Snapshot fields read by the goroutine so we don't race with
	// concurrent writes from Update.
	workdir := m.workdirPath
	artSrc := m.c.WS.Artifacts()
	if workdir == "" {
		return func() tea.Msg {
			return deploypage.PhaseEventMsg{Event: deployfeat.PhaseEvent{
				Phase: "cfn", Status: "failed", At: time.Now(),
				Message: "workdir not ready — AWS detection still running. Press [esc] back to welcome and re-launch.",
			}}
		}
	}

	// Fetch artifacts FIRST (synchronously here, since Run() blocks on
	// them anyway). The fetch is fast — ~10MB total — and gives the user
	// a clear "downloading…" event before CFN starts.
	ch := make(chan workspace.DeployEvent, 64)
	go func() {
		ch <- workspace.DeployEvent{Phase: "cfn", Status: "started", Message: "fetching CFN template + helm chart from GitHub release", At: time.Now()}

		artifacts, err := launch.FetchArtifacts(
			context.Background(),
			artSrc,
			workdir,
			params.MAVersion,
			params.Scope,
		)
		if err != nil {
			ch <- workspace.DeployEvent{Phase: "cfn", Status: "failed", Message: "artifact fetch: " + err.Error(), At: time.Now()}
			close(ch)
			return
		}
		ch <- workspace.DeployEvent{Phase: "cfn", Status: "progress", Message: "artifacts ready: " + filepathBase(artifacts.HelmChart), At: time.Now()}

		// Read the template body from disk.
		if artifacts.CFNTemplate != "" {
			body, err := osReadFile(artifacts.CFNTemplate)
			if err != nil {
				ch <- workspace.DeployEvent{Phase: "cfn", Status: "failed", Message: "read template: " + err.Error(), At: time.Now()}
				close(ch)
				return
			}
			params.TemplateBody = body
		}
		params.HelmChartPath = artifacts.HelmChart
		// Surface the skill-bundle path back to Update via the same
		// channel — wrapped in a synthetic event the page ignores but
		// the root model intercepts.
		ch <- workspace.DeployEvent{Phase: "skillbundle", Status: "ready", Message: artifacts.SkillBundle, At: time.Now()}

		// Stream the rest into the same channel.
		inner := make(chan workspace.DeployEvent, 64)
		go func() {
			_ = drv.Start(context.Background(), params, inner)
		}()
		for ev := range inner {
			ch <- ev
		}
		close(ch)
	}()
	return deployStreamCmd(ch)
}

// buildDeployParams maps wizard state into the workspace.DeployParams shape.
func buildDeployParams(st wizard.State, caller string) workspace.DeployParams {
	ns := st.Namespace
	if ns == "" {
		ns = "ma"
	}
	return workspace.DeployParams{
		Region:        st.Region,
		StackName:     st.StackName,
		Stage:         st.Stage,
		Scope:         st.Scope,
		VPCID:         st.VPCID,
		SubnetIDs:     st.SubnetIDs,
		VPCEndpoints:  st.VPCEndpoints,
		MAVersion:     "3.2.1",
		TLSMode:       st.TLSMode,
		Namespace:     ns,
		ReleaseName:   ns,
		EKSAccessARNs: st.AdditionalAccessARNs,
		CallerARN:     caller,
		// TemplateBody and HelmChartPath are populated by the
		// artifact-fetch path which lands in a future iteration.
	}
}

// deployStreamCmd reads from the driver's event channel one event at a
// time and feeds it back into Update as a deployStreamMsg, which the
// root re-issues to drain the next event — the canonical §4.3 channel-
// into-Update pattern.
func deployStreamCmd(ch <-chan workspace.DeployEvent) tea.Cmd {
	return func() tea.Msg {
		ev, ok := <-ch
		if !ok {
			return deployStreamDoneMsg{}
		}
		return deployStreamMsg{ev: ev, ch: ch}
	}
}

// deployStreamMsg wraps one event from the driver's channel along with
// the channel itself, so root can re-issue the read on the next Update.
type deployStreamMsg struct {
	ev workspace.DeployEvent
	ch <-chan workspace.DeployEvent
}

// deployStreamDoneMsg is sent when the channel closes (driver finished).
type deployStreamDoneMsg struct{}

// agentBin maps a feature.AgentCLI.Name to the actual binary on PATH.
// claude-code on PATH is `claude`; kiro-cli is `kiro-cli`.
func agentBin(name string) string {
	switch name {
	case "claude-code":
		return "claude"
	default:
		return name
	}
}

func (m *Model) detectAgentsCmd() tea.Cmd {
	det := m.c.WS.Agents()
	if det == nil {
		return func() tea.Msg { return msg.AgentsDetectedMsg{Agents: nil} }
	}
	return func() tea.Msg {
		ctx := context.Background()
		ag, err := det.Detect(ctx)
		return msg.AgentsDetectedMsg{Agents: ag, Err: err}
	}
}

// detectVPCsCmd schedules a describe-vpcs call for the given region.
func (m *Model) detectVPCsCmd(region string) tea.Cmd {
	svc := m.c.WS.AWS()
	if svc == nil {
		return nil
	}
	return func() tea.Msg {
		ctx := context.Background()
		vpcs, err := svc.ListVPCs(ctx, region)
		return msg.VPCsDetectedMsg{VPCs: vpcs, Err: err}
	}
}

// detectSubnetsCmd schedules a describe-subnets call.
func (m *Model) detectSubnetsCmd(region, vpcID string) tea.Cmd {
	svc := m.c.WS.AWS()
	if svc == nil || vpcID == "" {
		return nil
	}
	return func() tea.Msg {
		ctx := context.Background()
		subs, err := svc.ListSubnets(ctx, region, vpcID)
		return msg.SubnetsDetectedMsg{Subnets: subs, Err: err}
	}
}

// detectVPCEndpointsCmd schedules a describe-vpc-endpoints call.
func (m *Model) detectVPCEndpointsCmd(region, vpcID string) tea.Cmd {
	svc := m.c.WS.AWS()
	if svc == nil || vpcID == "" {
		return nil
	}
	return func() tea.Msg {
		ctx := context.Background()
		eps, err := svc.ListVPCEndpoints(ctx, region, vpcID)
		return msg.VPCEndpointsDetectedMsg{Endpoints: eps, Err: err}
	}
}

// dispatchPageCmd is a convenience wrapper that returns the cmd from a
// page dispatch without mutating the model.
func dispatchPageCmd(m *Model, message tea.Msg) tea.Cmd {
	_, cmd := m.dispatchPage(message)
	return cmd
}

// errAWSUnavailable is returned when no AWS service is wired (tests, or
// the user has no credentials yet).
var errAWSUnavailable = awsUnavailableErr{}

type awsUnavailableErr struct{}

func (awsUnavailableErr) Error() string { return "AWS not configured (run `aws configure`)" }

// Compile-time interface assertions.
var (
	_ tea.Model = (*Model)(nil)
	_           = feature.AWSIdentity{}
)

// clusterFromExports parses MIGRATIONS_EKS_CLUSTER_NAME out of any of
// the bash-style export values. Returns "" if not found.
func clusterFromExports(exports []feature.CFNExport) string {
	for _, e := range exports {
		parts := strings.Split(e.Value, ";")
		for _, p := range parts {
			p = strings.TrimSpace(strings.TrimPrefix(p, "export "))
			if idx := strings.Index(p, "MIGRATIONS_EKS_CLUSTER_NAME="); idx >= 0 {
				return strings.TrimSpace(p[idx+len("MIGRATIONS_EKS_CLUSTER_NAME="):])
			}
		}
	}
	return ""
}

// handleInstalledChoice translates the user's pick on the
// already-installed screen into the right post-Quit exec or navigation.
func (m *Model) handleInstalledChoice(c installed.ChosenMsg) tea.Cmd {
	switch c.Action {
	case installed.ActionOpenConsole:
		ns := "ma"
		cluster := c.Cluster
		if cluster == "" {
			cluster = "migration-eks-cluster-" + nzh(c.Stage, "dev") + "-" + nzh(c.Region, "us-east-1")
		}
		m.c.WS.SetHandoffWithPre(
			"kubectl",
			[]string{"--context=" + cluster, "-n", ns, "exec", "-it", "migration-console-0", "--", "/bin/bash"},
			m.c.WS.Workdir(),
			"aws",
			[]string{"eks", "update-kubeconfig", "--region", nzh(c.Region, "us-east-1"), "--name", cluster, "--alias", cluster},
			"setting up kubectl context",
		)
		return tea.Quit
	case installed.ActionAgentMode:
		m.mode = welcome.ModeAgent
		m.wizState.Stage = c.Stage
		m.wizState.Region = c.Region
		m.wizState.Namespace = "ma"
		return func() tea.Msg { return msg.NavigateMsg{To: msg.PageHandoff} }
	case installed.ActionReinstall:
		return func() tea.Msg {
			return msg.StatusMsg{
				Text: "Reinstall not yet implemented — use 'helm uninstall ma -n ma' then re-launch the TUI.",
				TTL:  10 * time.Second,
			}
		}
	case installed.ActionUninstall:
		// Uninstall is destructive — surface a modal confirm. On confirm
		// we set a goodbye banner with the actual shell commands and
		// quit; the user runs them in their shell. We don't drive the
		// destruction from inside the TUI (locked **r5** plus general
		// safety: better that the user sees and re-confirms each step).
		stage := nzh(c.Stage, "dev")
		region := nzh(c.Region, "us-east-1")
		stackName := "MA-" + capitalize(stage)
		cluster := nzh(c.Cluster, "migration-eks-cluster-"+stage+"-"+region)
		commands := strings.Join([]string{
			"# Step 1 — uninstall helm release",
			"helm uninstall ma -n ma --kube-context=" + cluster,
			"",
			"# Step 2 — delete the CFN stack (creates VPC/EKS will be torn down)",
			"aws cloudformation delete-stack --region " + region + " --stack-name " + stackName,
			"aws cloudformation wait stack-delete-complete --region " + region + " --stack-name " + stackName,
			"",
			"# Step 3 — clean up the local workdir (optional)",
			"rm -rf " + m.workdirPath,
		}, "\n")
		m.dialogs.Push(dialog.NewConfirm(
			"Uninstall Migration Assistant?",
			"This will tear down the helm release AND the CFN stack — about 15 minutes, irreversible (any in-flight migration data is lost). On confirm the TUI exits and prints the exact commands to run.",
			func() tea.Cmd {
				banner := "Uninstall steps — run these one at a time and verify each:\n\n" + commands
				m.c.WS.SetGoodbye(banner)
				m.quitting = true
				return tea.Quit
			},
			nil,
		))
		return nil
	}
	return nil
}

// capitalize titlecases the first character of stage so the CFN stack
// name follows the bootstrap.sh convention (dev → MA-Dev, prod → MA-Prod).
func capitalize(s string) string {
	if s == "" {
		return ""
	}
	if s[0] >= 'a' && s[0] <= 'z' {
		return string(s[0]-32) + s[1:]
	}
	return s
}

// nzh returns the first non-empty string, or def.
func nzh(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
