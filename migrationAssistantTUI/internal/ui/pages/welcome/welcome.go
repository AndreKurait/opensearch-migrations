// Package welcome implements the launch screen (UX.md §7).
//
// Job:
//   - Render detection summary: AWS identity, region, existing MA, agent CLIs, saved sessions.
//   - Offer mode picker (Manual / AI Agent).
//   - On enter: emit msg.NavigateMsg{To: PageIntent}.
package welcome

import (
	"fmt"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"
	lipgloss "charm.land/lipgloss/v2"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature/agents"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
)

// RefreshAWSMsg is fired by the welcome page when the user requests
// re-detection. The root model handles it by re-issuing detectAWSCmd.
type RefreshAWSMsg struct{}

// SwitchProfileMsg asks root to swap to a different AWS profile and
// re-run detection. v1 just tells the user how to do it; v2 will offer
// an inline picker.
type SwitchProfileMsg struct{ ProfileName string }

// Mode is the user's handoff choice (UX.md §2). Asked here, switchable
// until final handoff (locked **A**).
type Mode int

const (
	ModeManual Mode = iota
	ModeAgent
)

func (m Mode) String() string {
	if m == ModeAgent {
		return "AI Agent"
	}
	return "Manual"
}

// Model is the welcome page state.
type Model struct {
	c *common.Common

	cursor int // 0 = Manual, 1 = AI Agent

	// Detection summary, populated as background detection completes.
	identity  feature.AWSIdentity
	awsErr    error
	awsReady  bool
	maExports []feature.CFNExport

	agents      []feature.AgentCLI
	agentsReady bool

	tools      []feature.Tool
	toolsReady bool
	toolsErr   error

	// MA release version: detected at launch from GitHub. PinnedTag
	// reflects what this TUI binary was built against.
	latestMATag string
	pinnedMATag string
	maTagReady  bool

	// Resume prompt state
	resumeWorkdir string
	resumeStatus  string
	resumeStage   string
	hasResume     bool
}

// New constructs the welcome page.
func New(c *common.Common) *Model { return &Model{c: c} }

// Init returns nil — detection is launched by root once-per-session.
func (m *Model) Init() tea.Cmd { return nil }

// Update handles input and detection-result messages.
func (m *Model) Update(message tea.Msg) (*Model, tea.Cmd) {
	switch v := message.(type) {
	case tea.KeyPressMsg:
		switch {
		case key.Matches(v, m.c.Keys.Welcome.Up):
			if m.cursor > 0 {
				m.cursor--
			}
		case key.Matches(v, m.c.Keys.Welcome.Down):
			if m.cursor < 1 {
				m.cursor++
			}
		case key.Matches(v, m.c.Keys.Welcome.Choose):
			return m, func() tea.Msg { return msg.NavigateMsg{To: msg.PageIntent} }
		case key.Matches(v, m.c.Keys.Welcome.Refresh):
			// Reset detection state and let root re-run detect cmds.
			m.awsReady = false
			m.awsErr = nil
			m.agentsReady = false
			return m, func() tea.Msg { return RefreshAWSMsg{} }
		case key.Matches(v, m.c.Keys.Welcome.SwitchProfile):
			return m, func() tea.Msg { return SwitchProfileMsg{} }
		}
	case msg.AWSDetectedMsg:
		m.awsReady = true
		m.awsErr = v.Err
		m.identity = v.Identity
		m.maExports = v.MAExports
	case msg.AgentsDetectedMsg:
		m.agentsReady = true
		m.agents = v.Agents
	case msg.ToolsDetectedMsg:
		m.toolsReady = true
		m.tools = v.Tools
		m.toolsErr = v.Err
	case msg.MAReleaseDetectedMsg:
		m.maTagReady = true
		m.latestMATag = v.LatestTag
		m.pinnedMATag = v.PinnedTag
	case msg.WorkdirDetectedMsg:
		if v.Has {
			m.hasResume = true
			m.resumeWorkdir = v.Workdir
			m.resumeStatus = v.Status
			m.resumeStage = v.Stage
		}
	case msg.LayoutMsg:
		m.c.Width, m.c.Height = v.Width, v.Height
	}
	return m, nil
}

// SelectedMode returns the current cursor as a Mode.
func (m *Model) SelectedMode() Mode {
	if m.cursor == 1 {
		return ModeAgent
	}
	return ModeManual
}

// View renders the welcome screen.
func (m *Model) View() string {
	s := m.c.Styles
	var b strings.Builder

	b.WriteString(s.Header.Title.Render("OpenSearch Migration Assistant CLI"))
	b.WriteString("\n\n")

	b.WriteString(s.Page.Hint.Render(
		"Welcome. This tool helps you stand up the OpenSearch Migration\n" +
			"Assistant in your AWS account, then either drops you into the\n" +
			"migration console or hands off to an AI agent."))
	b.WriteString("\n\n")

	b.WriteString(s.Form.Label.Render("Detected:") + "\n")
	b.WriteString(m.detectionLines())
	b.WriteString("\n")

	b.WriteString(s.Form.Label.Render("How do you want to drive the migration?") + "\n\n")
	if m.hasResume {
		b.WriteString(s.Status.Warn.Render(
			"  ⚠ Existing install detected at "+m.resumeWorkdir+" (status: "+m.resumeStatus+")\n"+
				"    Pressing [enter] resumes; press [c] to clear and start fresh, or [d] to delete.") + "\n\n")
	}
	b.WriteString(m.modeChoice("Manual", "TUI walks setup, then drops you into a console shell", 0))
	b.WriteString(m.modeChoice("AI Agent", "TUI walks setup, then launches an AI agent for you", 1))
	b.WriteString("\n")

	b.WriteString(s.Footer.Hint.Render("[↑↓] choose   [enter] continue   [r] refresh   [a] switch AWS profile   [q] quit"))

	return s.Page.Container.Render(b.String())
}

func (m *Model) detectionLines() string {
	s := m.c.Styles
	var lines []string

	if !m.awsReady {
		lines = append(lines, s.Status.Info.Render("  AWS identity   resolving…"))
	} else if m.awsErr != nil {
		lines = append(lines, s.Status.Error.Render(fmt.Sprintf("  AWS identity   error: %v", truncate(m.awsErr.Error(), 200))))
		lines = append(lines, s.Status.Warn.Render("                 → fix creds (`aws sso login` / `mwinit -s`), then press [r] to retry, or [a] to switch profile"))
	} else {
		lines = append(lines, fmt.Sprintf("  AWS identity   %s / %s",
			m.identity.AccountID, m.identity.UserARN))
		lines = append(lines, fmt.Sprintf("  Region         %s", m.identity.Region))
		if len(m.maExports) == 0 {
			lines = append(lines, fmt.Sprintf("  Existing MA    none found in %s", m.identity.Region))
		} else {
			// Group by stage — MigrationsExportString-<stage>-<region>.
			stages := map[string]bool{}
			for _, e := range m.maExports {
				stage := stageFromExportName(e.Name, m.identity.Region)
				if stage != "" {
					stages[stage] = true
				}
			}
			stageList := ""
			for stage := range stages {
				if stageList != "" {
					stageList += ", "
				}
				stageList += stage
			}
			if stageList == "" {
				stageList = "<unknown>"
			}
			lines = append(lines, s.Status.Warn.Render(fmt.Sprintf(
				"  Existing MA    ⚠ install detected in %s (stages: %s)",
				m.identity.Region, stageList)))
			lines = append(lines, s.Status.Warn.Render(
				"                 → use a NEW stage in the wizard, or pick Skip-CFN to reuse the existing one"))
		}
	}

	if !m.agentsReady {
		lines = append(lines, s.Status.Info.Render("  AI agents      resolving…"))
	} else {
		first := true
		for _, a := range m.agents {
			label := "  AI agents      "
			if !first {
				label = "                 "
			}
			lines = append(lines, label+agents.AgentLine(a))
			first = false
		}
		if first {
			lines = append(lines, "  AI agents      (none detected)")
		}
	}

	// Required CLI tools (helm, kubectl, aws, docker). Fed by ToolDetector.
	if !m.toolsReady {
		lines = append(lines, s.Status.Info.Render("  CLI tools      resolving…"))
	} else if m.toolsErr != nil {
		lines = append(lines, s.Status.Error.Render(fmt.Sprintf("  CLI tools      error: %v", truncate(m.toolsErr.Error(), 200))))
	} else {
		var missingRequired []string
		var rows []string
		for _, t := range m.tools {
			marker := "✓"
			style := s.Status.Info
			ver := t.Version
			if !t.Installed() {
				marker = "✗"
				if t.Required {
					style = s.Status.Error
					missingRequired = append(missingRequired, t.Name)
				} else {
					style = s.Status.Warn
				}
				ver = "(not found)"
			}
			rows = append(rows, style.Render(fmt.Sprintf("%s %-8s %s", marker, t.Name, ver)))
		}
		first := true
		for _, r := range rows {
			label := "  CLI tools      "
			if !first {
				label = "                 "
			}
			lines = append(lines, label+r)
			first = false
		}
		if len(missingRequired) > 0 {
			lines = append(lines, s.Status.Error.Render(fmt.Sprintf(
				"                 → missing required: %s — install before launch",
				strings.Join(missingRequired, ", "))))
		}
	}

	// MA release version — always render so the user knows what they're
	switch {
	case !m.maTagReady:
		lines = append(lines, s.Status.Info.Render("  MA version     resolving latest…"))
	case m.latestMATag == "":
		lines = append(lines, fmt.Sprintf("  MA version     %s (pinned; latest unknown)", m.pinnedMATag))
	case m.latestMATag == m.pinnedMATag:
		lines = append(lines, fmt.Sprintf("  MA version     %s (latest)", m.pinnedMATag))
	default:
		lines = append(lines, s.Status.Warn.Render(fmt.Sprintf(
			"  MA version     %s (this TUI is pinned; latest is %s — update TUI to deploy newer)",
			m.pinnedMATag, m.latestMATag)))
	}

	return lipgloss.JoinVertical(lipgloss.Left, lines...)
}

// stageFromExportName extracts the stage name from a CFN export name
// of the shape "MigrationsExportString-<stage>-<region>". Returns ""
// when the format doesn't match.
func stageFromExportName(name, region string) string {
	// Real bootstrap.sh produces names like
	//   MigrationsExportString-dev-us-east-1
	//   MigrationsExportString-dev-us-east-1-Bucket
	prefix := "MigrationsExportString-"
	if !strings.HasPrefix(name, prefix) {
		return ""
	}
	rest := name[len(prefix):]
	regionDash := "-" + region
	if idx := strings.Index(rest, regionDash); idx >= 0 {
		return rest[:idx]
	}
	return ""
}

// truncate keeps an error string short enough to fit on a single
// terminal row without wrapping mid-line.
func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	if n < 4 {
		return s[:n]
	}
	return s[:n-3] + "…"
}

func (m *Model) modeChoice(label, sub string, idx int) string {
	s := m.c.Styles
	prefix := "    "
	style := s.List.Item
	if m.cursor == idx {
		prefix = "  ▸ "
		style = s.List.Selected
	}
	row := prefix + style.Render(label) + " — " + s.Header.Subtle.Render(sub) + "\n"
	return row
}
