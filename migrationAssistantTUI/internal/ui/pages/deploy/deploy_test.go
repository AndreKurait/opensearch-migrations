package deploy

import (
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	deployfeat "github.com/opensearch-project/opensearch-migrations/tui/internal/feature/deploy"
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

// pushEvent feeds one PhaseEvent into the page exactly the way the root
// model would when the deploy goroutine emits it.
func pushEvent(t *testing.T, m *Model, ev deployfeat.PhaseEvent) (*Model, tea.Cmd) {
	t.Helper()
	updated, cmd := m.Update(PhaseEventMsg{Event: ev})
	return updated, cmd
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

func TestDeployPhaseEventsCompleteFlow(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{Region: "us-east-1", Stage: "dev", StackName: "MA-Dev"})

	phases := []string{"cfn", "exports", "kubeconfig", "access", "images", "helm"}
	now := time.Now()
	var lastCmd tea.Cmd
	for _, p := range phases {
		m, _ = pushEvent(t, m, deployfeat.PhaseEvent{Phase: p, Status: "started", At: now})
		m, _ = pushEvent(t, m, deployfeat.PhaseEvent{Phase: p, Status: "progress", Message: "doing things", At: now})
		m, lastCmd = pushEvent(t, m, deployfeat.PhaseEvent{Phase: p, Status: "completed", At: now})
	}

	require.True(t, m.complete, "all phases completed → m.complete must be true")
	require.NotNil(t, lastCmd, "final completion event must produce CompletedMsg cmd")
	out := lastCmd()
	_, ok := out.(CompletedMsg)
	require.True(t, ok, "expected CompletedMsg, got %T", out)
}

func TestDeployFailedEventEmitsFailedMsg(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	_, cmd := pushEvent(t, m, deployfeat.PhaseEvent{
		Phase: "cfn", Status: "failed", Message: "stack rolled back", At: time.Now(),
	})
	require.NotNil(t, cmd)
	fm, ok := cmd().(FailedMsg)
	require.True(t, ok, "expected FailedMsg, got %T", cmd())
	require.Contains(t, fm.Err.Error(), "stack rolled back")
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

func TestDeployBackgroundKeyMarksCompleteAndEmitsBackgroundedMsg(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	require.False(t, m.complete)
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	require.True(t, m.complete)
	require.NotNil(t, cmd, "background key must emit BackgroundedMsg so root routes back to welcome")
	_, ok := cmd().(BackgroundedMsg)
	require.True(t, ok)
}

func TestDeployFullLogToggle(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	require.False(t, m.showLog)
	_, _ = m.Update(tea.KeyPressMsg{Code: 'l', Text: "l"})
	require.True(t, m.showLog)
	_, _ = m.Update(tea.KeyPressMsg{Code: 'l', Text: "l"})
	require.False(t, m.showLog)
}

func TestDeployFullLogCapsAt1000(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	now := time.Now()
	for i := 0; i < 1500; i++ {
		m, _ = pushEvent(t, m, deployfeat.PhaseEvent{
			Phase: "cfn", Status: "progress", Message: "noise", At: now,
		})
	}
	require.LessOrEqual(t, len(m.allEvents), 1000, "full log must cap at 1000 to bound memory on long deploys")
}

func TestDeployPhaseEventsCapAt50PerPhase(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	now := time.Now()
	for i := 0; i < 200; i++ {
		m, _ = pushEvent(t, m, deployfeat.PhaseEvent{
			Phase: "cfn", Status: "progress", Message: "noise", At: now,
		})
	}
	require.LessOrEqual(t, len(m.phases[0].Events), 50, "per-phase events should cap at 50")
}

func TestDeployUnknownPhaseAccumulatesIntoFullLog(t *testing.T) {
	t.Parallel()
	m := newDeploy(t)
	m.Begin(wizard.State{})
	m, _ = pushEvent(t, m, deployfeat.PhaseEvent{
		Phase: "nope", Status: "progress", Message: "should land in full log only", At: time.Now(),
	})
	require.Len(t, m.allEvents, 1, "even unknown phases must be appended to full log")
	require.Equal(t, "PENDING", m.phases[1].Status, "unknown phase must not affect known phases")
}
