package installed

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
)

func newPage(t *testing.T) *Model {
	t.Helper()
	return New(common.New(testutil.NewFakeWS(t)))
}

func TestSetExistingPicksFirstStage(t *testing.T) {
	t.Parallel()
	m := newPage(t)
	m.SetExisting("ma-cluster", "us-east-1", []string{"prod", "staging"})
	require.Equal(t, "ma-cluster", m.cluster)
	require.Equal(t, "prod", m.stage, "first stage in list should default")
}

func TestEnterEmitsChosenMsgWithAction(t *testing.T) {
	t.Parallel()
	m := newPage(t)
	m.SetExisting("ma-cluster", "us-east-1", []string{"dev"})

	// Down once → cursor=1 (Switch to AI Agent).
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.NotNil(t, cmd)
	out := cmd()
	cm, ok := out.(ChosenMsg)
	require.True(t, ok, "expected ChosenMsg, got %T", out)
	require.Equal(t, ActionAgentMode, cm.Action)
	require.Equal(t, "ma-cluster", cm.Cluster)
	require.Equal(t, "dev", cm.Stage)
}

func TestBackKeyEmitsBackMsg(t *testing.T) {
	t.Parallel()
	m := newPage(t)
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	require.NotNil(t, cmd)
	_, ok := cmd().(BackMsg)
	require.True(t, ok, "esc must emit BackMsg")
}

func TestUpDownClampToValidRange(t *testing.T) {
	t.Parallel()
	m := newPage(t)
	// Up at 0 stays at 0.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyUp})
	require.Equal(t, 0, m.cursor)
	// Down past last clamps at 3.
	for i := 0; i < 10; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	}
	require.Equal(t, 3, m.cursor)
}

func TestViewLabelsAllActions(t *testing.T) {
	t.Parallel()
	m := newPage(t)
	m.SetExisting("ma-cluster", "us-east-1", []string{"prod", "dev"})
	out := m.View()
	for _, want := range []string{
		"Migration Assistant already installed",
		"Cluster:",
		"Stage:",
		"Open the console",
		"Switch to AI Agent",
		"Reinstall",
		"Uninstall completely",
		"[b] back to welcome",
	} {
		require.True(t, strings.Contains(out, want), "view missing %q", want)
	}
	require.Contains(t, out, "(others detected: dev)", "secondary stages should be listed when more than one exists")
}
