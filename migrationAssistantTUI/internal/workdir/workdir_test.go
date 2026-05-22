package workdir

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"
)

// safeTempDir returns a temp dir under $HOME that the workdir guard
// will accept. On macOS, t.TempDir() lives under /var which is
// (correctly) on the deny list — so we mint our own.
func safeTempDir(t *testing.T) string {
	t.Helper()
	home, err := os.UserHomeDir()
	require.NoError(t, err)
	dir, err := os.MkdirTemp(filepath.Join(home, ".cache"), "ma-test-*")
	require.NoError(t, err)
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func TestResolveRejectsRootAndHome(t *testing.T) {
	t.Parallel()
	_, err := Resolve("/", "111", "us-east-1")
	require.ErrorIs(t, err, ErrUnsafeCwd)
	home, _ := os.UserHomeDir()
	if home != "" {
		_, err = Resolve(home, "111", "us-east-1")
		require.ErrorIs(t, err, ErrUnsafeCwd)
	}
}

func TestResolveRejectsForbiddenRoots(t *testing.T) {
	t.Parallel()
	for _, p := range []string{"/tmp", "/usr", "/opt", "/etc", "/var/log"} {
		_, err := Resolve(p, "111", "us-east-1")
		require.ErrorIsf(t, err, ErrUnsafeCwd, "expected reject for %s", p)
	}
}

func TestResolveAcceptsWritableSubdir(t *testing.T) {
	t.Parallel()
	dir := safeTempDir(t)
	out, err := Resolve(dir, "123456789012", "us-east-1")
	require.NoError(t, err)
	require.Equal(t, filepath.Join(dir, "opensearch-migration-123456789012-us-east-1"), out)
}

func TestEnsureLayoutCreatesSubdirs(t *testing.T) {
	t.Parallel()
	wd := filepath.Join(safeTempDir(t), "opensearch-migration-x-y")
	require.NoError(t, EnsureLayout(wd))
	for _, sub := range []string{"transcripts", "reports", "notes"} {
		fi, err := os.Stat(filepath.Join(wd, sub))
		require.NoError(t, err)
		require.True(t, fi.IsDir(), "%s should be a directory", sub)
	}
}

func TestStateRoundTrip(t *testing.T) {
	t.Parallel()
	wd := filepath.Join(safeTempDir(t), "opensearch-migration-x-y")
	s := State{AccountID: "111", Region: "us-east-1", MAVersion: "3.2.1", Stage: "dev", Mode: "manual", Status: "installed"}
	require.NoError(t, SaveState(wd, s))
	got, err := LoadState(wd)
	require.NoError(t, err)
	require.NotNil(t, got)
	require.Equal(t, "111", got.AccountID)
	require.Equal(t, "3.2.1", got.MAVersion)
	require.Equal(t, 1, got.SchemaVersion, "schema version should be set on save")
}

func TestInspectGuards(t *testing.T) {
	t.Parallel()
	wd := filepath.Join(safeTempDir(t), "opensearch-migration-x-y")

	// Fresh
	g, st, err := Inspect(wd, "111", "us-east-1", "3.2.1")
	require.NoError(t, err)
	require.Equal(t, GuardFresh, g)
	require.Nil(t, st)

	// Now save state and re-inspect.
	require.NoError(t, SaveState(wd, State{
		AccountID: "111", Region: "us-east-1", MAVersion: "3.2.1",
	}))
	g, st, err = Inspect(wd, "111", "us-east-1", "3.2.1")
	require.NoError(t, err)
	require.Equal(t, GuardOK, g)
	require.NotNil(t, st)

	// Different version → Overwrite.
	g, _, err = Inspect(wd, "111", "us-east-1", "3.3.0")
	require.NoError(t, err)
	require.Equal(t, GuardOverwrite, g)

	// Corrupt → GuardCorrupt.
	require.NoError(t, os.WriteFile(filepath.Join(wd, ".ma-state.json"), []byte("{not-json"), 0o600))
	g, _, _ = Inspect(wd, "111", "us-east-1", "3.2.1")
	require.Equal(t, GuardCorrupt, g)
}
