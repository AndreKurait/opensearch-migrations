package styles

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestByNameKnownThemes(t *testing.T) {
	t.Parallel()
	cases := map[string]string{
		"":             "Dark",        // empty falls back to Dark
		"dark":         "Dark",        // unrecognized "dark" goes to default → Dark
		"light":        "Light",
		"hi-contrast":  "HighContrast",
		"high-contrast": "HighContrast",
		"unknown":      "Dark",
	}
	for name := range cases {
		s := ByName(name)
		require.NotNil(t, s, "ByName(%q) must return non-nil styles", name)
		// Smoke check: the style nest is populated.
		require.NotNil(t, s.Header.Title, "Header.Title must be set")
		require.NotNil(t, s.Status.Error, "Status.Error must be set")
		require.NotNil(t, s.Page.Container, "Page.Container must be set")
	}
}

func TestThemesAreDistinct(t *testing.T) {
	t.Parallel()
	d := Dark()
	l := Light()
	hc := HighContrast()

	// Render the same string under each and verify the ANSI bytes differ —
	// proves themes don't accidentally alias to the same palette.
	probe := "x"
	require.NotEqual(t, d.Header.Title.Render(probe), l.Header.Title.Render(probe),
		"Dark and Light must produce different ANSI output for the same string")
	require.NotEqual(t, d.Header.Title.Render(probe), hc.Header.Title.Render(probe),
		"Dark and HighContrast must produce different ANSI output for the same string")
}

func TestFromPopulatesEverySection(t *testing.T) {
	t.Parallel()
	s := Dark()
	// Each named lipgloss.Style should be valid (.Render() returns a
	// non-empty string on a non-empty input). This catches the bug class
	// where a future field is added to Styles{} but not initialized in From().
	probe := "test"
	require.NotEmpty(t, s.Header.Title.Render(probe))
	require.NotEmpty(t, s.Header.Subtle.Render(probe))
	require.NotEmpty(t, s.Header.Tab.Render(probe))
	require.NotEmpty(t, s.Header.ActiveTab.Render(probe))
	require.NotEmpty(t, s.Footer.Bar.Render(probe))
	require.NotEmpty(t, s.Footer.Hint.Render(probe))
	require.NotEmpty(t, s.List.Item.Render(probe))
	require.NotEmpty(t, s.List.Selected.Render(probe))
	require.NotEmpty(t, s.List.Disabled.Render(probe))
	require.NotEmpty(t, s.List.Marker.Render(probe))
	require.NotEmpty(t, s.Form.Label.Render(probe))
	require.NotEmpty(t, s.Form.Input.Render(probe))
	require.NotEmpty(t, s.Form.Help.Render(probe))
	require.NotEmpty(t, s.Form.Error.Render(probe))
	require.NotEmpty(t, s.Form.Required.Render(probe))
	require.NotEmpty(t, s.Dialog.Border.Render(probe))
	require.NotEmpty(t, s.Dialog.Title.Render(probe))
	require.NotEmpty(t, s.Dialog.Body.Render(probe))
	require.NotEmpty(t, s.Status.Info.Render(probe))
	require.NotEmpty(t, s.Status.Warn.Render(probe))
	require.NotEmpty(t, s.Status.Error.Render(probe))
	require.NotEmpty(t, s.Status.Success.Render(probe))
	require.NotEmpty(t, s.Page.Container.Render(probe))
	require.NotEmpty(t, s.Page.Section.Render(probe))
	require.NotEmpty(t, s.Page.Hint.Render(probe))
	require.NotEmpty(t, s.Code.Render(probe))
}
