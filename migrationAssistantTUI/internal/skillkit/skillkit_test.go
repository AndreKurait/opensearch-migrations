package skillkit

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"
)

// makeBundle constructs a minimal `kiro-assistant.tar.gz`-style tarball for tests.
func makeBundle(t *testing.T, layout map[string]string) string {
	t.Helper()
	buf := &bytes.Buffer{}
	gz := gzip.NewWriter(buf)
	tw := tar.NewWriter(gz)
	for name, content := range layout {
		require.NoError(t, tw.WriteHeader(&tar.Header{
			Name: name, Mode: 0o644, Size: int64(len(content)),
			Typeflag: tar.TypeReg,
		}))
		_, err := tw.Write([]byte(content))
		require.NoError(t, err)
	}
	require.NoError(t, tw.Close())
	require.NoError(t, gz.Close())
	dir := t.TempDir()
	p := filepath.Join(dir, "bundle.tar.gz")
	require.NoError(t, os.WriteFile(p, buf.Bytes(), 0o600))
	return p
}

func TestInstallKiroPlacesUpstreamLayout(t *testing.T) {
	t.Parallel()
	tar := makeBundle(t, map[string]string{
		".kiro/agents/main.md":  "# Main agent",
		".kiro/skills/start.md": "# @start",
	})
	wd := t.TempDir()
	require.NoError(t, Install(wd, AgentKiro, tar))
	for _, p := range []string{".kiro/agents/main.md", ".kiro/skills/start.md"} {
		fi, err := os.Stat(filepath.Join(wd, p))
		require.NoError(t, err, p)
		require.False(t, fi.IsDir())
	}
}

func TestInstallClaudeCodeBuildsSkillsTree(t *testing.T) {
	t.Parallel()
	tar := makeBundle(t, map[string]string{
		"skills/start.md":    "# @start",
		"skills/snapshot.md": "# snapshot",
	})
	wd := t.TempDir()
	require.NoError(t, Install(wd, AgentClaudeCode, tar))
	root := filepath.Join(wd, ".claude", "skills", "opensearch-migration")
	for _, p := range []string{"start.md", "snapshot.md", "SKILL.md"} {
		_, err := os.Stat(filepath.Join(root, p))
		require.NoError(t, err, p)
	}
	idx, err := os.ReadFile(filepath.Join(root, "SKILL.md"))
	require.NoError(t, err)
	require.Contains(t, string(idx), "start.md")
	require.Contains(t, string(idx), "snapshot.md")
}

func TestInstallRejectsUnknownAgent(t *testing.T) {
	t.Parallel()
	tar := makeBundle(t, map[string]string{"x": "y"})
	require.Error(t, Install(t.TempDir(), Agent("q-developer"), tar))
}

func TestInstallRejectsPathTraversal(t *testing.T) {
	t.Parallel()
	buf := &bytes.Buffer{}
	gz := gzip.NewWriter(buf)
	tw := tar.NewWriter(gz)
	require.NoError(t, tw.WriteHeader(&tar.Header{
		Name: "../escape", Mode: 0o644, Size: 1, Typeflag: tar.TypeReg,
	}))
	_, _ = tw.Write([]byte("x"))
	require.NoError(t, tw.Close())
	require.NoError(t, gz.Close())
	dir := t.TempDir()
	p := filepath.Join(dir, "evil.tar.gz")
	require.NoError(t, os.WriteFile(p, buf.Bytes(), 0o600))

	err := Install(t.TempDir(), AgentKiro, p)
	require.Error(t, err)
	require.Contains(t, err.Error(), "unsafe tar entry")
}
