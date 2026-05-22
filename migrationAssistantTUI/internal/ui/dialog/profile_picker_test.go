package dialog_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/dialog"
)

func TestProfilePickerEnterCallsOnSubmit(t *testing.T) {
	t.Parallel()
	captured := ""
	s := dialog.NewStack()
	s.Push(dialog.NewProfilePicker(func(name string) tea.Cmd {
		captured = name
		return nil
	}))
	// Type "prod"
	for _, r := range "prod" {
		s.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	_, _, act := s.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, dialog.ActionClose, act)
	require.Equal(t, "prod", captured)
}

func TestProfilePickerEscCancelsWithoutSubmit(t *testing.T) {
	t.Parallel()
	called := false
	s := dialog.NewStack()
	s.Push(dialog.NewProfilePicker(func(name string) tea.Cmd {
		called = true
		return nil
	}))
	_, _, act := s.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	require.Equal(t, dialog.ActionClose, act)
	require.False(t, called, "esc must NOT call onSubmit")
}

func TestProfilePickerNilOnSubmitDoesNotPanic(t *testing.T) {
	t.Parallel()
	s := dialog.NewStack()
	s.Push(dialog.NewProfilePicker(nil))
	_, _, act := s.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, dialog.ActionClose, act)
}

func TestProfilePickerViewRendersTitle(t *testing.T) {
	t.Parallel()
	d := dialog.NewProfilePicker(nil)
	out := d.View(80, 24)
	require.True(t, strings.Contains(out, "Switch AWS profile"))
}

func TestProfilePickerIDIsStable(t *testing.T) {
	t.Parallel()
	require.Equal(t, "profile-picker", dialog.NewProfilePicker(nil).ID())
}

func TestProfilePickerInitReturnsNil(t *testing.T) {
	t.Parallel()
	require.Nil(t, dialog.NewProfilePicker(nil).Init())
}
