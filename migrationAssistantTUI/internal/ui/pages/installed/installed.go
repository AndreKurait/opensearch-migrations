// Package installed renders the "MA is already installed" screen
// (UX.md §13.1). When AWS detection finds an existing MigrationsExportString*
// CFN export, we skip the wizard entirely and offer four concrete actions:
//
//  1. Open the console (kubectl exec into migration-console-0)
//  2. Switch to AI Agent mode
//  3. Reinstall (uninstall helm release + redeploy)
//  4. Uninstall completely (helm + CFN stack)
//
// This is faster + safer than walking the wizard for an existing install.
package installed

import (
	"fmt"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
)

// Action is the user's choice. Root translates each into the right cmd.
type Action int

const (
	ActionOpenConsole Action = iota
	ActionAgentMode
	ActionReinstall
	ActionUninstall
)

// ChosenMsg is emitted when the user picks an action.
type ChosenMsg struct {
	Action  Action
	Cluster string
	Region  string
	Stage   string
}

// Model is the installed-MA page.
type Model struct {
	c *common.Common

	cursor   int
	cluster  string
	region   string
	stage    string
	stages   []string // when multiple installs exist, user picks
}

// New constructs an Installed page.
func New(c *common.Common) *Model { return &Model{c: c} }

// SetExisting tells the page which MA install(s) the user can act on.
// `stages` lists every detected stage; cluster/region come from CFN exports.
func (m *Model) SetExisting(cluster, region string, stages []string) {
	m.cluster = cluster
	m.region = region
	m.stages = stages
	if len(stages) > 0 {
		m.stage = stages[0]
	}
}

// Init returns nil.
func (m *Model) Init() tea.Cmd { return nil }

// Update handles key input.
func (m *Model) Update(message tea.Msg) (*Model, tea.Cmd) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		switch {
		case key.Matches(k, key.NewBinding(key.WithKeys("up", "k"))):
			if m.cursor > 0 {
				m.cursor--
			}
		case key.Matches(k, key.NewBinding(key.WithKeys("down", "j"))):
			if m.cursor < 3 {
				m.cursor++
			}
		case key.Matches(k, key.NewBinding(key.WithKeys("esc", "b"))):
			return m, func() tea.Msg { return BackMsg{} }
		case key.Matches(k, key.NewBinding(key.WithKeys("enter"))):
			return m, func() tea.Msg {
				return ChosenMsg{
					Action:  Action(m.cursor),
					Cluster: m.cluster,
					Region:  m.region,
					Stage:   m.stage,
				}
			}
		}
	}
	return m, nil
}

// BackMsg signals the user wants to leave the installed-action picker
// and proceed through the standard wizard instead.
type BackMsg struct{}

// View renders the action picker.
func (m *Model) View() string {
	s := m.c.Styles
	var b strings.Builder
	b.WriteString(s.Header.Title.Render("Migration Assistant already installed"))
	b.WriteString("\n\n")

	if m.cluster != "" {
		b.WriteString(fmt.Sprintf("  Cluster:  %s\n", m.cluster))
	}
	if m.region != "" {
		b.WriteString(fmt.Sprintf("  Region:   %s\n", m.region))
	}
	if m.stage != "" {
		b.WriteString(fmt.Sprintf("  Stage:    %s\n", m.stage))
	}
	if len(m.stages) > 1 {
		b.WriteString(fmt.Sprintf("  (others detected: %s)\n", strings.Join(m.stages[1:], ", ")))
	}
	b.WriteString("\n")

	b.WriteString(s.Form.Label.Render("What would you like to do?") + "\n\n")

	choices := []struct{ label, desc string }{
		{"Open the console",
			"kubectl exec into migration-console-0 — fastest path"},
		{"Switch to AI Agent mode",
			"Launch the agent CLI primed with the existing install context"},
		{"Reinstall",
			"helm uninstall + helm install (preserves CFN stack, ~5 min)"},
		{"Uninstall completely",
			"helm uninstall + cfn delete-stack (destroys EVERYTHING, ~15 min)"},
	}
	for i, c := range choices {
		marker := "    "
		st := s.List.Item
		if i == m.cursor {
			marker = "  ▸ "
			st = s.List.Selected
		}
		b.WriteString(marker + st.Render(c.label) + " — " + s.Header.Subtle.Render(c.desc) + "\n")
	}

	b.WriteString("\n")
	b.WriteString(s.Footer.Hint.Render("[↑↓] choose   [enter] continue   [b] back to welcome   [q] quit"))
	return s.Page.Container.Render(b.String())
}
