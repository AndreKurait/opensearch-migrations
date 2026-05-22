package dialog

import (
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"
)

// fakeDialog is a controllable Dialog for unit-testing the stack.
type fakeDialog struct {
	id      string
	action  Action
	replace Dialog
	updates int
	rendered string
}

func (f *fakeDialog) ID() string    { return f.id }
func (f *fakeDialog) Init() tea.Cmd { return nil }
func (f *fakeDialog) Update(m tea.Msg) (Dialog, tea.Cmd, Action) {
	f.updates++
	return f, nil, f.action
}
func (f *fakeDialog) View(w, h int) string { return f.rendered }
func (f *fakeDialog) Replacement() Dialog  { return f.replace }

func TestStackPushReturnsInit(t *testing.T) {
	t.Parallel()
	s := NewStack()
	cmd := s.Push(&fakeDialog{id: "a", action: ActionPropagate})
	require.Nil(t, cmd, "fakeDialog.Init returns nil")
	require.Equal(t, 1, s.Len())
}

func TestStackPropagateLeavesItemOnStack(t *testing.T) {
	t.Parallel()
	s := NewStack()
	d := &fakeDialog{id: "a", action: ActionPropagate}
	s.Push(d)
	_, _, act := s.Update(tea.KeyPressMsg{Code: 'x', Text: "x"})
	require.Equal(t, ActionPropagate, act)
	require.Equal(t, 1, s.Len())
}

func TestStackCloseRemovesTop(t *testing.T) {
	t.Parallel()
	s := NewStack()
	s.Push(&fakeDialog{id: "a", action: ActionClose})
	s.Update(tea.KeyPressMsg{Code: 'x'})
	require.Equal(t, 0, s.Len())
}

func TestStackReplaceWithReplacement(t *testing.T) {
	t.Parallel()
	s := NewStack()
	s.Push(&fakeDialog{id: "a", action: ActionReplace, replace: &fakeDialog{id: "b", action: ActionPropagate}})
	s.Update(tea.KeyPressMsg{Code: 'x'})
	require.Equal(t, 1, s.Len(), "replace should keep stack depth at 1")
	// Force another update — must reach the replacement (id=b).
	stack, _, _ := s.Update(tea.KeyPressMsg{Code: 'y'})
	require.Equal(t, 1, stack.Len())
}

// TestStackReplaceWithNilDoesNotPanic guards the bug class where a
// dialog returns ActionReplace but its Replacement() returns nil — we
// must coerce that to Close instead of pushing a nil top.
func TestStackReplaceWithNilDoesNotPanic(t *testing.T) {
	t.Parallel()
	s := NewStack()
	s.Push(&fakeDialog{id: "a", action: ActionReplace, replace: nil})
	require.NotPanics(t, func() {
		s.Update(tea.KeyPressMsg{Code: 'x'})
	})
	require.Equal(t, 0, s.Len(), "replace-with-nil must coerce to Close")
}

func TestStackPopOnEmptyIsNoop(t *testing.T) {
	t.Parallel()
	s := NewStack()
	s.Pop()
	require.Equal(t, 0, s.Len())
}

func TestStackUpdateOnEmptyPropagates(t *testing.T) {
	t.Parallel()
	s := NewStack()
	_, _, act := s.Update(tea.KeyPressMsg{Code: 'x'})
	require.Equal(t, ActionPropagate, act)
}

func TestStackViewEmpty(t *testing.T) {
	t.Parallel()
	s := NewStack()
	require.Equal(t, "", s.View(80, 24))
}
