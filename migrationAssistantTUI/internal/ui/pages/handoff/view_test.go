package handoff

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/intent"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/welcome"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

func newHandoff(t *testing.T) *Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	return New(common.New(ws))
}

func TestViewManualMode(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	st := wizard.State{Region: "us-east-1", Stage: "dev", Namespace: "ma"}
	out := m.View(welcome.ModeManual, intent.Captured{}, st)
	require.Contains(t, out, "Migration Assistant is installed")
	require.Contains(t, out, "kubectl --context=migration-eks-cluster-dev-us-east-1")
}

func TestViewAgentMode(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	out := m.View(welcome.ModeAgent, intent.Captured{}, st)
	require.Contains(t, out, "kiro-cli")
	require.Contains(t, out, "@start")
}

func TestUpdateEnterEmitsHandoffMsg(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}, welcome.ModeManual, intent.Captured{}, st)
	require.NotNil(t, cmd)
	out := cmd()
	hm, ok := out.(HandoffMsg)
	require.True(t, ok)
	require.True(t, strings.Contains(hm.Banner, "Manual handoff"))
}

func TestUpdateAgentEnterEmitsAgentBanner(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}, welcome.ModeAgent, intent.Captured{}, st)
	require.NotNil(t, cmd)
	hm := cmd().(HandoffMsg)
	require.Contains(t, hm.Banner, "AI agent")
	require.Contains(t, hm.Banner, "@start")
}

func TestUpdateUpDownMovesCursor(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	st := wizard.State{}
	for i := 0; i < 4; i++ {
		_, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown}, welcome.ModeManual, intent.Captured{}, st)
	}
	for i := 0; i < 4; i++ {
		_, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyUp}, welcome.ModeManual, intent.Captured{}, st)
	}
	// No assertion on internal cursor — just confirm no panic.
	require.NotEmpty(t, m.View(welcome.ModeManual, intent.Captured{}, st))
}

func TestInitReturnsNil(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	require.Nil(t, m.Init())
}

func TestShowCmdHotkeyDoesNotEmitBanner(t *testing.T) {
	t.Parallel()
	m := newHandoff(t)
	st := wizard.State{}
	_, cmd := m.Update(tea.KeyPressMsg{Code: 'c', Text: "c"}, welcome.ModeManual, intent.Captured{}, st)
	require.Nil(t, cmd)
}

func TestViewAgentModeWarnsOnMissingAgent(t *testing.T) {
	t.Parallel()
	// Default FakeWS has Agent="" so AgentBin returns empty.
	m := newHandoff(t)
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	out := m.View(welcome.ModeAgent, intent.Captured{}, st)
	require.Contains(t, out, "No agent CLI was detected")
	require.Contains(t, out, "Manual mode")
}

func TestViewAgentModeNoWarnWhenAgentInstalled(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.Agent = "claude"
	m := New(common.New(ws))
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	out := m.View(welcome.ModeAgent, intent.Captured{}, st)
	require.NotContains(t, out, "No agent CLI was detected")
	require.Equal(t, "claude", ws.AgentBin())
}
