// Package handoff is the final TUI screen (UX.md §11 / §12). It writes
// HANDOFF.md if mode==agent, and emits HandoffMsg with the exec banner.
//
// The TUI exits at this point (locked **r5**); main.go's deferred
// app.Shutdown then runs, and the user is dropped back into their shell.
// The actual `exec` of kubectl/agent-cli is the responsibility of a small
// post-TUI helper — see cmd/tui/main.go.
package handoff

import (
	"fmt"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/intent"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/welcome"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

// HandoffMsg is the final exit signal. The Banner is the "what to run
// next" hint we print after the TUI exits.
type HandoffMsg struct {
	Banner string
}

// Model is the handoff page.
type Model struct {
	c      *common.Common
	cursor int
}

// New constructs a handoff page.
func New(c *common.Common) *Model { return &Model{c: c} }

// Init returns nil.
func (m *Model) Init() tea.Cmd { return nil }

// Update handles selection.
func (m *Model) Update(message tea.Msg, mode welcome.Mode, cap intent.Captured, st wizard.State) (*Model, tea.Cmd) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		kk := m.c.Keys.Handoff
		switch {
		case key.Matches(k, kk.Open):
			// Resolve the actual exec target now and stash it on the workspace,
			// so post-Quit main can pick it up.
			bin, args, cwd := resolveExec(mode, st, m.c.WS)
			preBin, preArgs, preDesc := resolvePreExec(mode, st)
			m.c.WS.SetHandoffWithPre(bin, args, cwd, preBin, preArgs, preDesc)
			return m, func() tea.Msg { return HandoffMsg{Banner: m.banner(mode, st)} }
		case key.Matches(k, kk.ShowCmd):
			// no-op; the command is already rendered.
		}
		switch k.String() {
		case "up":
			if m.cursor > 0 {
				m.cursor--
			}
		case "down":
			if m.cursor < 2 {
				m.cursor++
			}
		}
	}
	return m, nil
}

// resolvePreExec returns the optional pre-exec step (bin, args, desc) the
// post-TUI runtime should run before the final syscall.Exec.
//
// Manual mode: `aws eks update-kubeconfig --region R --name CLUSTER --alias CLUSTER`
// so kubectl --context=CLUSTER works on the very next command.
//
// Agent mode: nothing — the agent's own skills know how to exec into the pod.
func resolvePreExec(mode welcome.Mode, st wizard.State) (string, []string, string) {
	if mode != welcome.ModeManual {
		return "", nil, ""
	}
	cluster := "migration-eks-cluster-" + nzh(st.Stage, "dev") + "-" + nzh(st.Region, "us-east-1")
	return "aws", []string{
		"eks", "update-kubeconfig",
		"--region", nzh(st.Region, "us-east-1"),
		"--name", cluster,
		"--alias", cluster,
	}, "setting up kubectl context"
}

func nzh(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

// resolveExec converts the chosen mode + wizard state into a concrete
// (bin, args, cwd) triple. Mirrors UX.md §11/§12.
func resolveExec(mode welcome.Mode, st wizard.State, ws workspaceShim) (string, []string, string) {
	cwd := ws.Workdir() // "" pre-detection; main.go's chdir is then a no-op
	switch mode {
	case welcome.ModeAgent:
		bin := ws.AgentBin()
		if bin == "" {
			// Fall back to kiro-cli; main.go's exec.LookPath fails with a
			// clear error if it's not installed (vs. a silent no-op).
			bin = "kiro-cli"
		}
		switch bin {
		case "claude", "claude-code":
			// Claude Code reads .claude/skills/opensearch-migration/ from cwd.
			return "claude", nil, cwd
		default:
			return "kiro-cli", []string{"chat", "--agent", "opensearch-migration", "@start"}, cwd
		}
	default:
		ns := st.Namespace
		if ns == "" {
			ns = "ma" // matches aws-bootstrap.sh default
		}
		ctx := "migration-eks-cluster-" + nzh(st.Stage, "dev") + "-" + nzh(st.Region, "us-east-1")
		return "kubectl", []string{"--context=" + ctx, "-n", ns, "exec", "-it", "migration-console-0", "--", "/bin/bash"}, cwd
	}
}

// workspaceShim is the subset of common.Workspace resolveExec uses.
// Defined here so the function can be tested without the full common.
type workspaceShim interface {
	Workdir() string
	AgentBin() string
}

// View renders.
func (m *Model) View(mode welcome.Mode, cap intent.Captured, st wizard.State) string {
	s := m.c.Styles
	var b strings.Builder
	b.WriteString(s.Header.Title.Render("Ready"))
	b.WriteString("\n\n")
	b.WriteString("Migration Assistant is installed.\n\n")

	switch mode {
	case welcome.ModeAgent:
		b.WriteString(s.Form.Label.Render("Hand off to your AI agent:"))
		b.WriteString("\n\n")
		b.WriteString(s.Code.Render(agentCmd(st, m.c.WS.AgentBin())))
		if m.c.WS.AgentBin() == "" {
			b.WriteString("\n\n")
			b.WriteString(s.Status.Warn.Render(
				"⚠ No agent CLI was detected on PATH at launch. The exec will fail with " +
					"\"executable file not found\" until you install kiro-cli or claude. " +
					"You can also press [esc] back to welcome and pick Manual mode."))
		}
	default:
		b.WriteString(s.Form.Label.Render("Open the migration console (interactive shell in pod):"))
		b.WriteString("\n\n")
		b.WriteString(s.Code.Render(kubectlCmd(st)))
	}

	b.WriteString("\n\n")
	b.WriteString(s.Footer.Hint.Render("[enter] open now   [c] show command   [q] quit"))
	return s.Page.Container.Render(b.String())
}

func (m *Model) banner(mode welcome.Mode, st wizard.State) string {
	switch mode {
	case welcome.ModeAgent:
		return "Handoff to AI agent.\n\n  " + agentCmd(st, m.c.WS.AgentBin()) + "\n\nThe TUI has written HANDOFF.md to the workdir; the agent's first action will be to read it."
	default:
		return "Manual handoff.\n\n  " + kubectlCmd(st) + "\n\nThe TUI exited; run the command above to enter the migration console."
	}
}

func kubectlCmd(st wizard.State) string {
	ns := st.Namespace
	if ns == "" {
		ns = "ma"
	}
	stage := nzh(st.Stage, "dev")
	region := nzh(st.Region, "us-east-1")
	cluster := "migration-eks-cluster-" + stage + "-" + region
	return fmt.Sprintf("kubectl --context=%s -n %s exec -it migration-console-0 -- /bin/bash", cluster, ns)
}

// agentCmd renders the literal command line we'll exec for the user's
// chosen agent CLI. agentBin is the resolved binary name from PATH
// detection ("claude", "kiro-cli", or "" when nothing is installed).
func agentCmd(st wizard.State, agentBin string) string {
	switch agentBin {
	case "claude", "claude-code":
		return "claude"
	case "":
		// Nothing detected — fall back to the kiro hint and let main.go
		// surface a clean exec.LookPath error if it's not on PATH.
		return "kiro-cli chat --agent opensearch-migration \"@start\""
	default:
		return agentBin + " chat --agent opensearch-migration \"@start\""
	}
}
