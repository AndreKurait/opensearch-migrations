package keys

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestPageHelpReturnsBindingsForKnownPage(t *testing.T) {
	t.Parallel()
	km := Default()
	for _, page := range []string{"welcome", "intent", "wizard", "review", "deploy", "handoff"} {
		entries := km.PageHelp(page)
		require.NotEmpty(t, entries, "page %q must have help entries", page)
		// Global "?" + "q" should always appear regardless of page.
		var hasHelp, hasQuit bool
		for _, e := range entries {
			if e.Key == "?" {
				hasHelp = true
			}
			if e.Key == "q" {
				hasQuit = true
			}
		}
		require.True(t, hasHelp, "page %q help must include the help binding", page)
		require.True(t, hasQuit, "page %q help must include the quit binding", page)
	}
}

func TestPageHelpUnknownPageReturnsGlobalOnly(t *testing.T) {
	t.Parallel()
	km := Default()
	entries := km.PageHelp("nonexistent")
	require.Len(t, entries, 2, "unknown page should fall back to just the global pair")
}

func TestFormatHelpAlignsKeys(t *testing.T) {
	t.Parallel()
	out := FormatHelp([]HelpEntry{
		{Key: "a", Desc: "alpha"},
		{Key: "longer", Desc: "beta"},
	})
	lines := strings.Split(out, "\n")
	require.Len(t, lines, 2)
	// Both lines should have the descriptions aligned at the same column —
	// look for "alpha" and "beta" appearing at the same starting offset.
	idx0 := strings.Index(lines[0], "alpha")
	idx1 := strings.Index(lines[1], "beta")
	require.Equal(t, idx0, idx1, "key column must be padded so descriptions align")
}

func TestFormatHelpEmpty(t *testing.T) {
	t.Parallel()
	require.Equal(t, "", FormatHelp(nil))
}
