package app_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/app"
)

func TestNewWithoutAWSStillSucceeds(t *testing.T) {
	// AWS_PROFILE and friends might not be set in CI; New() must still
	// return a working App with a non-nil Workspace().
	a, err := app.New(context.Background(), app.Config{Version: "test"})
	require.NoError(t, err)
	require.NotNil(t, a)
	t.Cleanup(a.Shutdown)

	ws := a.Workspace()
	require.Equal(t, "test", ws.Version())
	require.NotEmpty(t, ws.Cwd())
	require.NotNil(t, ws.Agents(), "agents detector must always be wired")
	require.NotNil(t, ws.Artifacts(), "artifact source must always be wired")
}

func TestSetGoodbyeRoundTrip(t *testing.T) {
	a, err := app.New(context.Background(), app.Config{})
	require.NoError(t, err)
	t.Cleanup(a.Shutdown)
	require.Empty(t, a.Goodbye())
	a.SetGoodbye("see you")
	require.Equal(t, "see you", a.Goodbye())
}

func TestSetWorkdirRoundTrip(t *testing.T) {
	a, err := app.New(context.Background(), app.Config{})
	require.NoError(t, err)
	t.Cleanup(a.Shutdown)
	require.Empty(t, a.Workdir())
	a.SetWorkdir("/tmp/x")
	require.Equal(t, "/tmp/x", a.Workdir())
}

func TestSetHandoffRoundTrip(t *testing.T) {
	a, err := app.New(context.Background(), app.Config{})
	require.NoError(t, err)
	t.Cleanup(a.Shutdown)
	a.SetHandoff(app.HandoffCommand{Bin: "kubectl", Args: []string{"version"}, Cwd: "/tmp"})
	got := a.HandoffCommand()
	require.Equal(t, "kubectl", got.Bin)
	require.Equal(t, []string{"version"}, got.Args)
	require.Equal(t, "/tmp", got.Cwd)
}

func TestSetAgentBinRoundTrip(t *testing.T) {
	a, err := app.New(context.Background(), app.Config{})
	require.NoError(t, err)
	t.Cleanup(a.Shutdown)
	require.Empty(t, a.AgentBin())
	a.SetAgentBin("kiro-cli")
	require.Equal(t, "kiro-cli", a.AgentBin())
}

func TestShutdownIdempotent(t *testing.T) {
	a, err := app.New(context.Background(), app.Config{})
	require.NoError(t, err)
	require.NotPanics(t, func() {
		a.Shutdown()
		a.Shutdown()
		a.Shutdown()
	})
}
