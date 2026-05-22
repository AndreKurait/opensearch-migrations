package dialog_test

import (
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/dialog"
)

func TestStackPushPopLen(t *testing.T) {
	t.Parallel()
	s := dialog.NewStack()
	require.Zero(t, s.Len())
	s.Push(dialog.NewError("title", "body"))
	require.Equal(t, 1, s.Len())
	s.Pop()
	require.Zero(t, s.Len())
	// Pop on empty should not panic.
	s.Pop()
}

func TestErrorDialogClosesOnEnter(t *testing.T) {
	t.Parallel()
	s := dialog.NewStack()
	s.Push(dialog.NewError("AccessDenied", "ec2:DescribeVpcs"))
	require.Equal(t, 1, s.Len())
	_, _, act := s.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, dialog.ActionClose, act)
	require.Zero(t, s.Len())
}

func TestErrorDialogClosesOnEsc(t *testing.T) {
	t.Parallel()
	s := dialog.NewStack()
	s.Push(dialog.NewError("AccessDenied", "ec2:DescribeVpcs"))
	_, _, act := s.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	require.Equal(t, dialog.ActionClose, act)
	require.Zero(t, s.Len())
}

func TestErrorDialogPropagatesNonDismiss(t *testing.T) {
	t.Parallel()
	s := dialog.NewStack()
	s.Push(dialog.NewError("title", "body"))
	_, _, act := s.Update(tea.KeyPressMsg{Code: 'x', Text: "x"})
	require.Equal(t, dialog.ActionPropagate, act)
	require.Equal(t, 1, s.Len())
}

func TestConfirmDialogYesRunsCallback(t *testing.T) {
	t.Parallel()
	called := false
	s := dialog.NewStack()
	s.Push(dialog.NewConfirm("Reuse this workspace?", "same account+region+version", func() tea.Cmd {
		called = true
		return nil
	}, nil))
	_, _, act := s.Update(tea.KeyPressMsg{Code: 'y', Text: "y"})
	require.Equal(t, dialog.ActionClose, act)
	require.True(t, called)
}

func TestConfirmDialogNoRunsCallback(t *testing.T) {
	t.Parallel()
	called := false
	s := dialog.NewStack()
	s.Push(dialog.NewConfirm("Overwrite?", "different account", nil, func() tea.Cmd {
		called = true
		return nil
	}))
	_, _, act := s.Update(tea.KeyPressMsg{Code: 'n', Text: "n"})
	require.Equal(t, dialog.ActionClose, act)
	require.True(t, called)
}

func TestConfirmDialogTabTogglesCursor(t *testing.T) {
	t.Parallel()
	s := dialog.NewStack()
	s.Push(dialog.NewConfirm("title", "prompt", nil, nil))
	_, _, act := s.Update(tea.KeyPressMsg{Code: tea.KeyTab})
	require.Equal(t, dialog.ActionPropagate, act)
	require.Equal(t, 1, s.Len(), "tab should not close the dialog")
}
