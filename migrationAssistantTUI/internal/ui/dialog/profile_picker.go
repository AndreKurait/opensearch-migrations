package dialog

import (
	"strings"

	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/textinput"
	tea "charm.land/bubbletea/v2"
	lipgloss "charm.land/lipgloss/v2"
)

// ProfilePicker is a tiny dialog that asks for an AWS profile name.
//
// Calls onSubmit with the typed value when the user presses enter; the
// dialog closes regardless. Pressing esc cancels (onSubmit not called).
type ProfilePicker struct {
	id       string
	input    textinput.Model
	onSubmit func(name string) tea.Cmd
}

// NewProfilePicker constructs a profile-picker dialog. onSubmit may be nil.
func NewProfilePicker(onSubmit func(name string) tea.Cmd) *ProfilePicker {
	t := textinput.New()
	t.Placeholder = "default"
	t.SetWidth(40)
	t.Focus()
	return &ProfilePicker{id: "profile-picker", input: t, onSubmit: onSubmit}
}

func (p *ProfilePicker) ID() string    { return p.id }
func (p *ProfilePicker) Init() tea.Cmd { return nil }

func (p *ProfilePicker) Update(message tea.Msg) (Dialog, tea.Cmd, Action) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		switch {
		case key.Matches(k, key.NewBinding(key.WithKeys("esc"))):
			return p, nil, ActionClose
		case key.Matches(k, key.NewBinding(key.WithKeys("enter"))):
			val := strings.TrimSpace(p.input.Value())
			var cmd tea.Cmd
			if p.onSubmit != nil {
				cmd = p.onSubmit(val)
			}
			return p, cmd, ActionClose
		}
	}
	var cmd tea.Cmd
	p.input, cmd = p.input.Update(message)
	return p, cmd, ActionPropagate
}

func (p *ProfilePicker) View(w, h int) string {
	border := lipgloss.NewStyle().BorderStyle(lipgloss.RoundedBorder()).Padding(1, 2)
	title := lipgloss.NewStyle().Bold(true).Render("Switch AWS profile")
	var b strings.Builder
	b.WriteString(title)
	b.WriteString("\n\n")
	b.WriteString("Profile name (leave blank for 'default'):\n  ")
	b.WriteString(p.input.View())
	b.WriteString("\n\n")
	b.WriteString("[enter] confirm   [esc] cancel")
	return border.Render(b.String())
}

func (p *ProfilePicker) Replacement() Dialog { return nil }
