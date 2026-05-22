package deploy

import (
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

func newDeploy(t *testing.T) *Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	c := common.New(ws)
	return New(c)
}

// drainTicks fires the tick cmd N times in a row, dispatching each
// resulting tickAdvance back into the model. This is faster than waiting
// for tea.Tick and is sufficient to drive the simulation deterministically.
func drainTicks(t *testing.T, m *Model, n int) {
	t.Helper()
	for i := 0; i < n; i++ {
		var cmd tea.Cmd
		m, cmd = m.Update(tickAdvance{})
		_ = cmd // ignore — next iteration sends a fresh tickAdvance anyway
	}
}

func TestDeployBeginInitializesPhases(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	cmd := m.Begin(wizard.State{Region: "us-east-1", Stage: "dev", StackName: "MA-Dev"})
	require.NotNil(t, cmd)

	require.Len(t, m.phases, 6)
	// Begin pre-flips the first phase to IN_PROGRESS so users see
	// activity immediately, before the driver's first event lands.
	require.Equal(t, "IN_PROGRESS", m.phases[0].Status)
	for _, p := range m.phases[1:] {
		require.Equal(t, "PENDING", p.Status)
	}
	require.False(t, m.complete)
}

func TestDeployTickAdvancesPhases(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.TickInterval = time.Microsecond
	m.Begin(wizard.State{Region: "us-east-1", Stage: "dev", StackName: "MA-Dev"})

	// 5 phases × 3 events each = 15 ticks needed for completion. Loop
	// generously to absorb any drift.
	for i := 0; i < 20; i++ {
		var cmd tea.Cmd
		m, cmd = m.Update(tickAdvance{})
		if cmd != nil {
			out := cmd()
			if _, ok := out.(CompletedMsg); ok {
				require.True(t, m.complete)
				return
			}
		}
	}
	t.Fatalf("deploy did not signal completion after 20 ticks")
}

func TestDeployViewRendersAllPhases(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{Region: "us-east-1", Stage: "dev", StackName: "MA-Dev"})
	out := m.View()
	require.Contains(t, out, "Phase 1/6: CloudFormation")
	require.Contains(t, out, "Phase 2/6: CFN exports")
	require.Contains(t, out, "Phase 3/6: kubectl context")
	require.Contains(t, out, "Phase 4/6: EKS access")
	require.Contains(t, out, "Phase 5/6: Image mirror (ECR)")
	require.Contains(t, out, "Phase 6/6: Helm install")
}

func TestDeployBackgroundKeyMarksComplete(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	require.False(t, m.complete)
	_, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	require.True(t, m.complete)
}

// TestDeployTickProducesProgressEvents asserts each phase accumulates
// event lines as it ticks.
func TestDeployTickProducesProgressEvents(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{Region: "us-east-1", Stage: "dev"})
	// One tick → one event in the first phase.
	m, _ = m.Update(tickAdvance{})
	require.Len(t, m.phases[0].Events, 1)
	out := m.View()
	require.Contains(t, out, "event #1")
}

// silence unused-symbol noise.
var _ = drainTicks
