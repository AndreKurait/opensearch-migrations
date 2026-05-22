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

// ws is the test workspaceShim implementation.
type ws struct{ wd, agent string }

func (w ws) Workdir() string  { return w.wd }
func (w ws) AgentBin() string { return w.agent }

func TestResolveExecManual(t *testing.T) {
	t.Parallel()
	st := wizard.State{Region: "us-east-1", Stage: "dev", Namespace: "ma"}
	bin, args, _ := resolveExec(welcome.ModeManual, st, ws{})
	require.Equal(t, "kubectl", bin)
	got := strings.Join(args, " ")
	require.Contains(t, got, "--context=migration-eks-cluster-dev-us-east-1")
	require.Contains(t, got, "-n ma")
	require.Contains(t, got, "exec -it migration-console-0")
}

func TestResolveExecManualDefaultsNamespace(t *testing.T) {
	t.Parallel()
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	_, args, _ := resolveExec(welcome.ModeManual, st, ws{})
	require.Contains(t, strings.Join(args, " "), "-n ma")
}

func TestResolveExecAgentDefaultsToKiro(t *testing.T) {
	t.Parallel()
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	bin, args, _ := resolveExec(welcome.ModeAgent, st, ws{})
	require.Equal(t, "kiro-cli", bin)
	require.Contains(t, strings.Join(args, " "), "@start")
}

func TestResolveExecAgentClaude(t *testing.T) {
	t.Parallel()
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	bin, _, cwd := resolveExec(welcome.ModeAgent, st, ws{wd: "/tmp/wd", agent: "claude"})
	require.Equal(t, "claude", bin)
	require.Equal(t, "/tmp/wd", cwd, "agent must be exec'd in the workdir so .claude/ is found")
}

func TestResolveExecAgentKiroExplicit(t *testing.T) {
	t.Parallel()
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	bin, args, cwd := resolveExec(welcome.ModeAgent, st, ws{wd: "/tmp/wd", agent: "kiro-cli"})
	require.Equal(t, "kiro-cli", bin)
	require.Equal(t, "/tmp/wd", cwd)
	require.Contains(t, args, "opensearch-migration")
}

func TestKubectlCmdSubstrings(t *testing.T) {
	t.Parallel()
	cmd := kubectlCmd(wizard.State{Region: "us-west-2", Stage: "prod", Namespace: "ma"})
	require.Contains(t, cmd, "kubectl --context=migration-eks-cluster-prod-us-west-2")
	require.Contains(t, cmd, "-n ma")
	require.Contains(t, cmd, "migration-console-0")
}

func TestAgentCmd(t *testing.T) {
	t.Parallel()
	require.Contains(t, agentCmd(wizard.State{}), "@start")
}

func TestUpdateOpenEmitsHandoffMsg(t *testing.T) {
	t.Parallel()
	fws := testutil.NewFakeWS(t)
	c := common.New(fws)
	m := New(c)
	st := wizard.State{Region: "us-east-1", Stage: "dev"}
	_, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}, welcome.ModeManual, intent.Captured{}, st)
	require.NotNil(t, cmd)
	out := cmd()
	hm, ok := out.(HandoffMsg)
	require.True(t, ok)
	require.True(t, strings.Contains(hm.Banner, "Manual handoff"))
}
