package agents

import (
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

func TestClassifyDelta(t *testing.T) {
	t.Parallel()
	cases := []struct {
		local, latest string
		want          feature.VersionDelta
	}{
		{"2.1.140", "2.1.140", feature.DeltaNone},
		{"2.1.140", "2.1.147", feature.DeltaPatch},
		{"2.1.140", "2.2.0", feature.DeltaMinor},
		{"2.1.140", "3.0.0", feature.DeltaMajor},
		{"2.1.150", "2.1.140", feature.DeltaAhead},
		{"v2.1.140", "2.1.140", feature.DeltaNone},
		{"", "2.1.140", feature.DeltaUnknown},
		{"2.1.140", "", feature.DeltaUnknown},
		{"garbage", "2.1.140", feature.DeltaUnknown},
	}
	for _, c := range cases {
		got := classifyDelta(c.local, c.latest)
		require.Equal(t, c.want, got, "classify(%q,%q)", c.local, c.latest)
	}
}

func TestParseSemverStripsPrefixesAndSuffixes(t *testing.T) {
	t.Parallel()
	got, ok := parseSemver("v2.1.140-beta.1+build")
	require.True(t, ok)
	require.Equal(t, [3]int{2, 1, 140}, got)

	got, ok = parseSemver("claude 2.1.140 (foo)")
	require.True(t, ok)
	require.Equal(t, [3]int{2, 1, 140}, got)

	_, ok = parseSemver("nope")
	require.False(t, ok)
}

func TestAgentLine(t *testing.T) {
	t.Parallel()

	notInstalled := AgentLine(feature.AgentCLI{Name: "kiro-cli"})
	require.Contains(t, notInstalled, "not installed")

	noLocalVer := AgentLine(feature.AgentCLI{Name: "kiro-cli", Path: "/x"})
	require.Contains(t, noLocalVer, "version unknown")

	onlineUnknown := AgentLine(feature.AgentCLI{
		Name: "kiro-cli", Path: "/x", LocalVersion: "0.4.1",
	})
	require.Contains(t, onlineUnknown, "online version unknown")

	upToDate := AgentLine(feature.AgentCLI{
		Name: "claude-code", Path: "/x", LocalVersion: "2.1.140",
		LatestVersion: "2.1.140", BehindBy: feature.DeltaNone,
	})
	require.NotContains(t, upToDate, "behind")

	behind := AgentLine(feature.AgentCLI{
		Name: "claude-code", Path: "/x", LocalVersion: "2.1.140",
		LatestVersion: "2.1.147", BehindBy: feature.DeltaPatch,
	})
	require.Contains(t, behind, "patch behind")
	require.Contains(t, behind, "⚠")
}
