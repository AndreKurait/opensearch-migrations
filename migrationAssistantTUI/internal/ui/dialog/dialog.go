// Package dialog provides a stack-based modal overlay (PLAN §4.6).
//
// Rules:
//   - The active dialog (top of stack) gets first crack at messages.
//   - It returns one of {ActionPropagate, ActionClose, ActionReplace}
//     to let the host decide what happens next.
//   - The render layer composes overlay over the page using lipgloss.Place.
package dialog

import (
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"
	lipgloss "charm.land/lipgloss/v2"
)

// Action is the verdict a dialog returns from Update.
type Action int

const (
	// ActionPropagate forwards the message to the host's normal handling.
	ActionPropagate Action = iota
	// ActionClose pops the dialog off the stack.
	ActionClose
	// ActionReplace replaces the top dialog with the one returned in Replacement.
	ActionReplace
)

// Dialog is the contract every modal implements.
type Dialog interface {
	ID() string
	Init() tea.Cmd
	Update(message tea.Msg) (Dialog, tea.Cmd, Action)
	View(width, height int) string
	// Replacement is consulted only when Update returns ActionReplace.
	Replacement() Dialog
}

// Stack is a LIFO of Dialogs.
type Stack struct{ items []Dialog }

func NewStack() *Stack { return &Stack{} }

// Push adds a dialog to the top.
func (s *Stack) Push(d Dialog) { s.items = append(s.items, d) }

// Pop removes the top dialog (no-op when empty).
func (s *Stack) Pop() {
	if len(s.items) == 0 {
		return
	}
	s.items = s.items[:len(s.items)-1]
}

// Len returns the stack size.
func (s *Stack) Len() int { return len(s.items) }

// Update lets the top dialog handle a message.
func (s *Stack) Update(message tea.Msg) (*Stack, tea.Cmd, Action) {
	if len(s.items) == 0 {
		return s, nil, ActionPropagate
	}
	top := s.items[len(s.items)-1]
	updated, cmd, act := top.Update(message)
	switch act {
	case ActionClose:
		s.Pop()
	case ActionReplace:
		s.items[len(s.items)-1] = updated.Replacement()
	default:
		s.items[len(s.items)-1] = updated
	}
	return s, cmd, act
}

// View renders the top dialog centered over the available area. Returns
// "" when empty.
func (s *Stack) View(w, h int) string {
	if len(s.items) == 0 {
		return ""
	}
	body := s.items[len(s.items)-1].View(w, h)
	return lipgloss.Place(w, h, lipgloss.Center, lipgloss.Center, body)
}

// ---- prebuilt dialogs ----------------------------------------------------

// ErrorDialog is the modal variant of ErrorMsg routing.
type ErrorDialog struct {
	id     string
	title  string
	body   string
}

// NewError constructs an error modal.
func NewError(title, body string) *ErrorDialog {
	return &ErrorDialog{id: "error", title: title, body: body}
}

func (e *ErrorDialog) ID() string  { return e.id }
func (e *ErrorDialog) Init() tea.Cmd { return nil }

func (e *ErrorDialog) Update(message tea.Msg) (Dialog, tea.Cmd, Action) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		// Close on enter or esc — both intuitive.
		dismiss := key.NewBinding(key.WithKeys("enter", "esc", "q"))
		if key.Matches(k, dismiss) {
			return e, nil, ActionClose
		}
	}
	return e, nil, ActionPropagate
}

func (e *ErrorDialog) View(w, h int) string {
	border := lipgloss.NewStyle().BorderStyle(lipgloss.RoundedBorder()).Padding(1, 2)
	title := lipgloss.NewStyle().Bold(true).Render(e.title)
	var b strings.Builder
	b.WriteString(title)
	b.WriteString("\n\n")
	b.WriteString(e.body)
	b.WriteString("\n\n")
	b.WriteString("[enter] dismiss")
	return border.Render(b.String())
}

func (e *ErrorDialog) Replacement() Dialog { return nil }

// ConfirmDialog is yes/no.
type ConfirmDialog struct {
	id, title, prompt string
	yesLabel, noLabel string
	onYes, onNo       func() tea.Cmd
	cursor            int // 0 = yes, 1 = no
}

// NewConfirm builds a yes/no modal. onYes/onNo may be nil.
func NewConfirm(title, prompt string, onYes, onNo func() tea.Cmd) *ConfirmDialog {
	return &ConfirmDialog{
		id: "confirm", title: title, prompt: prompt,
		yesLabel: "Yes", noLabel: "No",
		onYes: onYes, onNo: onNo,
	}
}

func (c *ConfirmDialog) ID() string    { return c.id }
func (c *ConfirmDialog) Init() tea.Cmd { return nil }

func (c *ConfirmDialog) Update(message tea.Msg) (Dialog, tea.Cmd, Action) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		switch {
		case key.Matches(k, key.NewBinding(key.WithKeys("left", "right", "tab"))):
			c.cursor = 1 - c.cursor
		case key.Matches(k, key.NewBinding(key.WithKeys("y", "Y"))):
			c.cursor = 0
			return c, runOrNil(c.onYes), ActionClose
		case key.Matches(k, key.NewBinding(key.WithKeys("n", "N", "esc"))):
			c.cursor = 1
			return c, runOrNil(c.onNo), ActionClose
		case key.Matches(k, key.NewBinding(key.WithKeys("enter"))):
			if c.cursor == 0 {
				return c, runOrNil(c.onYes), ActionClose
			}
			return c, runOrNil(c.onNo), ActionClose
		}
	}
	return c, nil, ActionPropagate
}

func (c *ConfirmDialog) View(w, h int) string {
	border := lipgloss.NewStyle().BorderStyle(lipgloss.RoundedBorder()).Padding(1, 2)
	title := lipgloss.NewStyle().Bold(true).Render(c.title)
	yes := c.yesLabel
	no := c.noLabel
	if c.cursor == 0 {
		yes = lipgloss.NewStyle().Bold(true).Render("▸ " + yes)
		no = "  " + no
	} else {
		yes = "  " + yes
		no = lipgloss.NewStyle().Bold(true).Render("▸ " + no)
	}
	var b strings.Builder
	b.WriteString(title)
	b.WriteString("\n\n")
	b.WriteString(c.prompt)
	b.WriteString("\n\n")
	b.WriteString(yes + "    " + no)
	b.WriteString("\n\n")
	b.WriteString("[y/n] choose   [enter] confirm   [esc] cancel")
	return border.Render(b.String())
}

func (c *ConfirmDialog) Replacement() Dialog { return nil }

func runOrNil(f func() tea.Cmd) tea.Cmd {
	if f == nil {
		return nil
	}
	return f()
}
