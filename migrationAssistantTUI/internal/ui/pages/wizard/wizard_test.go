package wizard_test

import (
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

func newWizard(t *testing.T) *wizard.Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	c := common.New(ws)
	return wizard.New(c)
}

func TestWizardDefaultStateMatchesScriptDefaults(t *testing.T) {
	t.Parallel()
	w := newWizard(t)
	st := w.CurrentState()
	require.Equal(t, "us-east-1", st.Region)
	require.Equal(t, "dev", st.Stage)
	require.Equal(t, "create-vpc", st.Scope)
	require.Equal(t, "self-signed", st.TLSMode)
	require.Equal(t, "ma", st.Namespace)
	require.Equal(t, "MA-Dev", st.StackName)
	require.Equal(t, []string{"s3", "ecr", "ecrDocker"}, st.VPCEndpoints)
}

func TestWizardEnterAdvancesStep(t *testing.T) {
	t.Parallel()
	w := newWizard(t)
	for i := 0; i < 5; i++ {
		var cmd tea.Cmd
		w, cmd = w.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
		require.Nil(t, cmd, "non-final steps should not emit a cmd")
	}
}

func TestWizardLastEnterEmitsCompletedMsg(t *testing.T) {
	t.Parallel()
	w := newWizard(t)
	// 11 steps total, but with default scope=create-vpc the VPC/Subnets/Endpoints
	// steps are skipped — so 8 enters complete the wizard.
	var cmd tea.Cmd
	for i := 0; i < 8; i++ {
		w, cmd = w.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	require.NotNil(t, cmd, "final enter must emit CompletedMsg cmd")
	out := cmd()
	completed, ok := out.(wizard.CompletedMsg)
	require.True(t, ok, "expected wizard.CompletedMsg, got %T", out)
	require.Equal(t, "us-east-1", completed.State.Region)
}

func TestWizardScopePickerToggles(t *testing.T) {
	t.Parallel()
	w := newWizard(t)
	// Step into "Deployment scope" — with the new 11-step layout that's
	// identity → region → stage → scope.
	w, _ = w.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // identity
	w, _ = w.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // region
	w, _ = w.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // stage
	// Now down twice → "skip-cfn".
	w, _ = w.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	w, _ = w.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	w, _ = w.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, "skip-cfn", w.CurrentState().Scope)
}
