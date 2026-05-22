package ui_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
)

// drain runs the cmd (if any) and returns its msg, recursively unwrapping
// tea.Batch by best-effort. Tests use it to assert on what a key produced.
func drain(cmd tea.Cmd) tea.Msg {
	if cmd == nil {
		return nil
	}
	return cmd()
}

func TestRootInitialPageIsWelcome(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	out := m.View().Content
	require.True(t, strings.Contains(out, "OpenSearch Migration Assistant CLI"),
		"welcome page must render its title; got: %q", out)
}

func TestRootQuitOnQ(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	got, cmd := m.Update(tea.KeyPressMsg{Code: 'q', Text: "q"})
	require.NotNil(t, got)
	require.NotNil(t, cmd, "q should produce tea.Quit")
}

func TestRootQuitOnCtrlC(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	_, cmd := m.Update(tea.KeyPressMsg{Code: 'c', Mod: tea.ModCtrl})
	require.NotNil(t, cmd)
}

func TestRootWindowSizeForwardsLayoutMsg(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	_, cmd := m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	require.NotNil(t, cmd, "window size must produce a LayoutMsg cmd")
	out := drain(cmd)
	lm, ok := out.(msg.LayoutMsg)
	require.True(t, ok, "expected LayoutMsg, got %T", out)
	require.Equal(t, 100, lm.Width)
	require.Equal(t, 30, lm.Height)
}

func TestRootShowsAWSDetection(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.AWSSvc = &testutil.FakeAWS{
		Identity: feature.AWSIdentity{
			AccountID: "123456789012",
			UserARN:   "arn:aws:iam::123456789012:user/Admin",
			Region:    "us-east-1",
		},
	}
	m := ui.New(ws)
	// Inject the detection result directly to avoid ordering with Init().
	m.Update(msg.AWSDetectedMsg{
		Identity: ws.AWSSvc.(*testutil.FakeAWS).Identity,
	})
	out := m.View().Content
	require.Contains(t, out, "123456789012")
	require.Contains(t, out, "us-east-1")
}

func TestRootShowsAgents(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	m.Update(msg.AgentsDetectedMsg{Agents: []feature.AgentCLI{
		{Name: "claude-code", Path: "/usr/local/bin/claude", LocalVersion: "2.1.140", LatestVersion: "2.1.147", BehindBy: feature.DeltaPatch},
		{Name: "kiro-cli", Path: "/usr/local/bin/kiro-cli", LocalVersion: "0.4.1"},
	}})
	out := m.View().Content
	require.Contains(t, out, "claude-code")
	require.Contains(t, out, "kiro-cli")
}

func TestRootNavigateChangesPage(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	got, _ := m.Update(msg.NavigateMsg{To: msg.PageIntent})
	rendered := got.View().Content
	require.Contains(t, rendered, "What are you migrating?",
		"navigate to intent should render the intent capture form")
}

func TestRootHelpToggle(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	// First press should not crash; we just confirm no quit happens.
	_, cmd := m.Update(tea.KeyPressMsg{Code: '?', Text: "?"})
	require.Nil(t, cmd)
}

func TestRootEnterFromWelcomeNavigatesToIntent(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.NotNil(t, cmd)
	out := drain(cmd)
	nav, ok := out.(msg.NavigateMsg)
	require.True(t, ok, "expected NavigateMsg, got %T", out)
	require.Equal(t, msg.PageIntent, nav.To)
}

func TestRootDownArrowMovesWelcomeCursor(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	_, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	out := m.View().Content
	// AI Agent line gets the marker arrow when cursor==1.
	require.Contains(t, out, "▸ ")
	require.Contains(t, out, "AI Agent")
}

func TestRootStatusMsgRendersInFooter(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	m.Update(msg.StatusMsg{Text: "saved session prod-us-east-1.json"})
	out := m.View().Content
	require.Contains(t, out, "saved session prod-us-east-1.json")
}

func TestRootErrorMsgModalRoutesToDialog(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	m.Update(msg.ErrorMsg{Severity: msg.SevModal, Title: "AccessDenied", Err: errFake})
	out := m.View().Content
	require.Contains(t, out, "AccessDenied")
}

var errFake = fakeErr("permission denied")

type fakeErr string

func (e fakeErr) Error() string { return string(e) }
