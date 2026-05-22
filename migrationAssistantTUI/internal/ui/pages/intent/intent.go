// Package intent renders the pre-wizard intent capture form (UX.md §3).
//
// **v1 simplification:** structured source/target fields are deferred —
// the agent (or migration console) asks for those when needed. Here we
// collect just the free-text migration goal that gets piped into
// HANDOFF.md (UX.md §12.4).
//
// Skippable via [k] (recorded so the skill kit knows it must ask later).
package intent

import (
	"strings"

	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/textarea"
	tea "charm.land/bubbletea/v2"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
)

// Captured is the result of a completed (or skipped) intent step.
type Captured struct {
	Skipped       bool
	MigrationGoal string
}

// CapturedMsg is fired when the user finishes the form.
type CapturedMsg struct{ Result Captured }

// Model is the intent capture page state.
type Model struct {
	c *common.Common

	goal textarea.Model
	err  string

	skipped bool
	done    bool
}

// New constructs a fresh intent page.
func New(c *common.Common) *Model {
	t := textarea.New()
	t.Placeholder = "Migrate prod search index from on-prem ES 7.10 to OpenSearch 2.x with zero downtime…"
	t.SetWidth(70)
	t.SetHeight(5)
	t.Focus()
	return &Model{c: c, goal: t}
}

// Init returns nil — pure form state.
func (m *Model) Init() tea.Cmd { return nil }

// Update handles input.
func (m *Model) Update(message tea.Msg) (*Model, tea.Cmd) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		kk := m.c.Keys.Intent
		switch {
		case key.Matches(k, kk.Skip):
			m.skipped = true
			m.done = true
			return m, m.emit()
		case key.Matches(k, kk.Continue):
			m.done = true
			return m, m.emit()
		}
	}
	var cmd tea.Cmd
	m.goal, cmd = m.goal.Update(message)
	return m, cmd
}

func (m *Model) emit() tea.Cmd {
	out := m.captured()
	return tea.Batch(
		func() tea.Msg { return CapturedMsg{Result: out} },
		func() tea.Msg { return msg.NavigateMsg{To: msg.PageWizard} },
	)
}

func (m *Model) captured() Captured {
	if m.skipped {
		return Captured{Skipped: true}
	}
	return Captured{MigrationGoal: sanitize(m.goal.Value())}
}

// sanitize strips control chars and excess whitespace from free-text fields.
func sanitize(s string) string {
	s = strings.TrimSpace(s)
	var b strings.Builder
	for _, r := range s {
		if r == '\n' || r == '\t' || (r >= ' ' && r < 0x7f) || r >= 0xa0 {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// View renders the form.
func (m *Model) View() string {
	s := m.c.Styles
	var b strings.Builder

	b.WriteString(s.Header.Title.Render("What are you migrating?"))
	b.WriteString("\n\n")

	b.WriteString(s.Page.Hint.Render(
		"One sentence is fine. We'll pass it verbatim to the AI agent\n" +
			"(or echo it on the migration-console banner) so you don't have\n" +
			"to re-explain. Source / target details are collected later."))
	b.WriteString("\n\n")

	b.WriteString(s.Form.Label.Render("Migration goal"))
	b.WriteString("\n")
	b.WriteString(m.goal.View())
	b.WriteString("\n")

	if m.err != "" {
		b.WriteString("\n" + s.Form.Error.Render("error: "+m.err) + "\n")
	}

	b.WriteString("\n" + s.Footer.Hint.Render("[ctrl+s] continue   [k] skip (fill in console later)"))
	return s.Page.Container.Render(b.String())
}

// Result returns what the user captured. Empty when not yet done.
func (m *Model) Result() (Captured, bool) {
	if !m.done {
		return Captured{}, false
	}
	return m.captured(), true
}
