package review_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/review"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

func newReview(t *testing.T) *review.Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	return review.New(common.New(ws))
}

func TestEnterEmitsLaunchMsg(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.NotNil(t, cmd)
	_, ok := cmd().(review.LaunchMsg)
	require.True(t, ok)
}

func TestCopyKeyEmitsStatusHint(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	_, cmd := m.Update(tea.KeyPressMsg{Code: 'c', Text: "c"})
	require.NotNil(t, cmd, "[c] should produce a status hint")
	out := cmd()
	require.NotNil(t, out, "status hint must materialize")
}

func TestEnterBlockedOnStageConflict(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	m.SetExistingStages([]string{"dev"})
	st := wizard.State{Region: "us-east-1", Stage: "dev", Scope: "create-vpc",
		StackName: "MA-Dev", Source: "published", TLSMode: "self-signed"}
	// View first so the page records the conflicting state.
	_ = m.View(st)
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Nil(t, cmd, "blocked review must not emit LaunchMsg")
}

func TestEnterAllowedWhenStageNotConflicting(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	m.SetExistingStages([]string{"prod"})
	st := wizard.State{Region: "us-east-1", Stage: "dev", Scope: "create-vpc",
		StackName: "MA-Dev", Source: "published", TLSMode: "self-signed"}
	_ = m.View(st)
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.NotNil(t, cmd)
	_, ok := cmd().(review.LaunchMsg)
	require.True(t, ok)
}

func TestViewIncludesCommandSummary(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	st := wizard.State{
		Region: "us-east-1", Stage: "dev", Scope: "create-vpc",
		StackName: "MA-Dev", Source: "published", Version: "3.2.1",
		TLSMode: "self-signed",
	}
	out := stripANSI(m.View(st))
	for _, want := range []string{
		"Review", "Equivalent command", "aws-bootstrap.sh",
		"This will create:", "CFN stack 'MA-Dev'",
		"EKS cluster", "Helm release 'migration-assistant'",
		"Estimated cost", "[enter] launch",
	} {
		require.True(t, strings.Contains(out, want), "view missing %q", want)
	}
}

func TestViewMentionsPCAWarningOnPCACreate(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	st := wizard.State{
		Region: "us-east-1", Stage: "dev", Scope: "create-vpc",
		StackName: "MA-Dev", Source: "published",
		TLSMode: "pca-create",
	}
	out := stripANSI(m.View(st))
	require.Contains(t, out, "Private CA")
	require.Contains(t, out, "irreversible")
}

func TestViewSkipCFN(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	st := wizard.State{
		Region: "us-east-1", Stage: "dev", Scope: "skip-cfn",
		Source: "published", TLSMode: "none",
	}
	out := stripANSI(m.View(st))
	require.Contains(t, out, "(skipping CFN")
}

func TestInitReturnsNil(t *testing.T) {
	t.Parallel()
	m := newReview(t)
	require.Nil(t, m.Init())
}

// stripANSI removes ANSI escape sequences so substring matches don't
// fail on color codes.
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
