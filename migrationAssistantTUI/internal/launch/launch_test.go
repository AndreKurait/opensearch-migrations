package launch_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/launch"
)

type sessionPayload struct {
	Region string `json:"region"`
	Stage  string `json:"stage"`
}

func TestSaveAndLoadWizardSessionRoundTrip(t *testing.T) {
	t.Parallel()
	wd := t.TempDir()
	in := sessionPayload{Region: "us-west-2", Stage: "staging"}
	require.NoError(t, launch.SaveWizardSession(wd, in))

	var out sessionPayload
	has, err := launch.LoadWizardSession(wd, &out)
	require.NoError(t, err)
	require.True(t, has)
	require.Equal(t, in, out)
}

func TestLoadWizardSessionMissingReturnsFalse(t *testing.T) {
	t.Parallel()
	wd := t.TempDir()
	var out sessionPayload
	has, err := launch.LoadWizardSession(wd, &out)
	require.NoError(t, err)
	require.False(t, has, "missing file is not an error; should report has=false")
}

func TestLoadWizardSessionCorruptReturnsError(t *testing.T) {
	t.Parallel()
	wd := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(wd, ".ma-session.json"), []byte("{not valid json"), 0o600))
	var out sessionPayload
	has, err := launch.LoadWizardSession(wd, &out)
	require.Error(t, err, "corrupt JSON must surface as an error")
	require.False(t, has)
}

func TestSaveWizardSessionEmptyWorkdirRejected(t *testing.T) {
	t.Parallel()
	require.Error(t, launch.SaveWizardSession("", struct{}{}))
}
