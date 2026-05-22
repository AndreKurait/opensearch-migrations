package welcome_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/welcome"
)

func newPage(t *testing.T) *welcome.Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	return welcome.New(common.New(ws))
}

func TestEnterEmitsNavigateToIntent(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, cmd := p.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.NotNil(t, cmd)
	out := cmd()
	nav, ok := out.(msg.NavigateMsg)
	require.True(t, ok)
	require.Equal(t, msg.PageIntent, nav.To)
}

func TestDownThenEnterChoosesAgent(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, _ = p.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	require.Equal(t, welcome.ModeAgent, p.SelectedMode())
	_, _ = p.Update(tea.KeyPressMsg{Code: tea.KeyUp})
	require.Equal(t, welcome.ModeManual, p.SelectedMode())
}

func TestRKeyEmitsRefreshMsg(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, cmd := p.Update(tea.KeyPressMsg{Code: 'r', Text: "r"})
	require.NotNil(t, cmd)
	_, ok := cmd().(welcome.RefreshAWSMsg)
	require.True(t, ok)
}

func TestAKeyEmitsSwitchProfileMsg(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, cmd := p.Update(tea.KeyPressMsg{Code: 'a', Text: "a"})
	require.NotNil(t, cmd)
	_, ok := cmd().(welcome.SwitchProfileMsg)
	require.True(t, ok)
}

func TestAWSDetectionPopulatesView(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, _ = p.Update(msg.AWSDetectedMsg{
		Identity: feature.AWSIdentity{
			AccountID: "111122223333",
			UserARN:   "arn:aws:iam::111122223333:user/Builder",
			Region:    "eu-west-1",
		},
	})
	out := stripANSI(p.View())
	require.Contains(t, out, "111122223333")
	require.Contains(t, out, "eu-west-1")
}

func TestAWSDetectionErrorShowsCredHint(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, _ = p.Update(msg.AWSDetectedMsg{Err: errFake("InvalidClientToken")})
	out := stripANSI(p.View())
	require.Contains(t, out, "AWS identity   error")
	require.Contains(t, out, "InvalidClientToken")
	require.Contains(t, out, "[r] to retry")
	require.Contains(t, out, "[a] to switch profile")
}

func TestAgentsDetectionRendersInline(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, _ = p.Update(msg.AgentsDetectedMsg{Agents: []feature.AgentCLI{
		{Name: "claude-code", Path: "/usr/local/bin/claude", LocalVersion: "2.1.140", LatestVersion: "2.1.147", BehindBy: feature.DeltaPatch},
		{Name: "kiro-cli", Path: "", LocalVersion: ""},
	}})
	out := stripANSI(p.View())
	require.Contains(t, out, "claude-code")
	require.Contains(t, out, "kiro-cli")
	require.Contains(t, out, "2.1.140")
	require.Contains(t, out, "patch behind")
}

func TestLayoutMsgUpdatesSize(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, _ = p.Update(msg.LayoutMsg{Width: 120, Height: 40})
	// No assertion on size internals — just ensure no panic and View renders.
	require.NotEmpty(t, p.View())
}

func TestModeStringer(t *testing.T) {
	t.Parallel()
	require.Equal(t, "Manual", welcome.ModeManual.String())
	require.Equal(t, "AI Agent", welcome.ModeAgent.String())
}

type errFake string

func (e errFake) Error() string { return string(e) }

func stripANSI(s string) string {
	var b strings.Builder
	inEsc := false
	for _, r := range s {
		if inEsc {
			if r == 'm' || (r >= '@' && r <= '~') {
				inEsc = false
			}
			continue
		}
		if r == 0x1b {
			inEsc = true
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}
