package intent_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/intent"
)

func newPage(t *testing.T) *intent.Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	return intent.New(common.New(ws))
}

func TestSkipKeyEmitsCapturedAndNavigate(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, cmd := p.Update(tea.KeyPressMsg{Code: 'k', Text: "k"})
	require.NotNil(t, cmd)

	out := drainBatch(cmd)
	var sawCaptured, sawNav bool
	for _, m := range out {
		if c, ok := m.(intent.CapturedMsg); ok {
			sawCaptured = true
			require.True(t, c.Result.Skipped, "skip path must mark Captured.Skipped=true")
		}
		if n, ok := m.(msg.NavigateMsg); ok {
			sawNav = true
			require.Equal(t, msg.PageWizard, n.To)
		}
	}
	require.True(t, sawCaptured)
	require.True(t, sawNav)
}

func TestContinueAlwaysAdvances(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	// ctrl+s on empty form is fine — goal is optional in v1.
	_, cmd := p.Update(tea.KeyPressMsg{Code: 's', Mod: tea.ModCtrl})
	require.NotNil(t, cmd)
}

func TestResultBeforeDoneIsZero(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	got, ok := p.Result()
	require.False(t, ok)
	require.Equal(t, "", got.MigrationGoal)
}

func TestSkipResultIsMarkedSkipped(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	_, _ = p.Update(tea.KeyPressMsg{Code: 'k', Text: "k"})
	got, ok := p.Result()
	require.True(t, ok)
	require.True(t, got.Skipped)
}

func TestViewRendersTitleAndFooterHint(t *testing.T) {
	t.Parallel()
	p := newPage(t)
	out := stripANSI(p.View())
	for _, want := range []string{
		"What are you migrating?",
		"Migration goal",
		"[ctrl+s] continue",
		"[k] skip",
	} {
		require.True(t, strings.Contains(out, want), "view missing %q", want)
	}
}

// drainBatch unrolls a tea.Batch cmd into its child messages.
func drainBatch(c tea.Cmd) []tea.Msg {
	if c == nil {
		return nil
	}
	out := c()
	if out == nil {
		return nil
	}
	if cmds, ok := out.(tea.BatchMsg); ok {
		var msgs []tea.Msg
		for _, sub := range cmds {
			msgs = append(msgs, drainBatch(sub)...)
		}
		return msgs
	}
	return []tea.Msg{out}
}

// stripANSI removes ANSI escape sequences so assertions match plain text.
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
