package config_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/config"
)

// withConfigDir overrides the user-config-dir lookup for the test by
// pointing XDG_CONFIG_HOME / Apple's per-user config root at t.TempDir().
// macOS resolves UserConfigDir via $HOME/Library/Application Support, so
// we override $HOME instead. Both work since UserConfigDir falls back.
func withConfigDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, ".config"))
	return dir
}

func TestDefaultThemeIsDark(t *testing.T) {
	t.Parallel()
	require.Equal(t, "dark", config.Default().Theme)
}

func TestLoadMissingReturnsDefault(t *testing.T) {
	withConfigDir(t)
	c, p, err := config.Load()
	require.NoError(t, err)
	require.NotEmpty(t, p)
	require.Equal(t, "dark", c.Theme)
}

func TestSaveAndLoadRoundTrip(t *testing.T) {
	withConfigDir(t)
	in := config.Config{
		Theme:                "light",
		SkippedTUIVersions:   []string{"0.0.1", "0.0.2"},
		LastTUIVersionCheck:  "2026-05-22T00:00:00Z",
	}
	require.NoError(t, config.Save(in))

	got, _, err := config.Load()
	require.NoError(t, err)
	require.Equal(t, "light", got.Theme)
	require.Equal(t, []string{"0.0.1", "0.0.2"}, got.SkippedTUIVersions)
	require.Equal(t, "2026-05-22T00:00:00Z", got.LastTUIVersionCheck)
}

func TestLoadCorruptReturnsDefaultAndError(t *testing.T) {
	withConfigDir(t)
	p, err := config.Path()
	require.NoError(t, err)
	require.NoError(t, os.MkdirAll(filepath.Dir(p), 0o755))
	require.NoError(t, os.WriteFile(p, []byte("{not valid json"), 0o600))

	got, _, err := config.Load()
	require.Error(t, err)
	require.Equal(t, "dark", got.Theme, "corrupt file must fall back to default rather than panicking")
}

func TestPathStable(t *testing.T) {
	t.Parallel()
	p1, err := config.Path()
	require.NoError(t, err)
	p2, err := config.Path()
	require.NoError(t, err)
	require.Equal(t, p1, p2, "Path() must be deterministic across calls")
}
