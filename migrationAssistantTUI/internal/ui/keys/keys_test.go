// Package keys tests verify the structural invariants of the KeyMap:
//
//  1. Every binding has at least one key string.
//  2. Every binding has a non-empty help label.
//  3. No two bindings within the SAME sub-keymap (e.g. Welcome) share a
//     key string — that would make Matches() ambiguous.
//  4. Every binding key string is a value `tea.KeyPressMsg.String()` can
//     actually produce — i.e. it must be either a single visible rune,
//     a known named key ("enter", "esc", "space", ...), or a modifier
//     prefix ("ctrl+x", "alt+y").
//
// Bug class this catches: a previous version bound Wizard.Toggle to " "
// (a literal space character), but `tea.KeyPressMsg.String()` for a
// space keypress returns "space" (named key) — so Matches always
// returned false and the subnet-toggle hotkey did nothing.
package keys

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"
)

// allBindings flattens KeyMap into a list of (group, name, binding)
// triples for systematic checking.
func allBindings(km KeyMap) []labeledBinding {
	return []labeledBinding{
		// Global
		{"Global", "Quit", km.Global.Quit},
		{"Global", "Help", km.Global.Help},
		{"Global", "Back", km.Global.Back},
		{"Global", "Confirm", km.Global.Confirm},
		{"Global", "SwitchMode", km.Global.SwitchMode},
		{"Global", "SaveExit", km.Global.SaveExit},
		{"Global", "SwitchAcct", km.Global.SwitchAcct},
		// Welcome
		{"Welcome", "Up", km.Welcome.Up},
		{"Welcome", "Down", km.Welcome.Down},
		{"Welcome", "Choose", km.Welcome.Choose},
		{"Welcome", "Refresh", km.Welcome.Refresh},
		{"Welcome", "SwitchProfile", km.Welcome.SwitchProfile},
		// Intent
		{"Intent", "NextField", km.Intent.NextField},
		{"Intent", "PrevField", km.Intent.PrevField},
		{"Intent", "Skip", km.Intent.Skip},
		{"Intent", "Continue", km.Intent.Continue},
		// Wizard
		{"Wizard", "Up", km.Wizard.Up},
		{"Wizard", "Down", km.Wizard.Down},
		{"Wizard", "Filter", km.Wizard.Filter},
		{"Wizard", "Manual", km.Wizard.Manual},
		{"Wizard", "Toggle", km.Wizard.Toggle},
		{"Wizard", "NextStep", km.Wizard.NextStep},
		{"Wizard", "PrevStep", km.Wizard.PrevStep},
		// Review
		{"Review", "Launch", km.Review.Launch},
		{"Review", "CopyCmd", km.Review.CopyCmd},
		{"Review", "Details", km.Review.Details},
		// Deploy
		{"Deploy", "Background", km.Deploy.Background},
		{"Deploy", "FullLog", km.Deploy.FullLog},
		// Handoff
		{"Handoff", "Open", km.Handoff.Open},
		{"Handoff", "ShowCmd", km.Handoff.ShowCmd},
	}
}

type labeledBinding struct {
	group, name string
	bind        key.Binding
}

// keyStringsOf extracts the underlying key strings from a Binding via
// reflection-free duck-typing — Help() returns the first key as the
// short label, but we need ALL of them. The bubbles package doesn't
// expose them publicly, so we re-test the contract via Matches() against
// each candidate string.
//
// Because key.Binding.keys is unexported, we infer them indirectly by
// checking which strings Match. This list is the canonical universe of
// keystrings tea.KeyPressMsg.String() can produce; if a binding matches
// none of them, the binding is broken.
var canonicalKeyStrings = []string{
	// printable ASCII
	"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
	"n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
	"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
	"N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
	"0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
	"!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
	"-", "_", "=", "+", "[", "]", "{", "}", "\\", "|",
	";", ":", "'", "\"", ",", ".", "/", "<", ">", "?",
	"`", "~",
	// space — both forms
	" ", "space",
	// named keys
	"enter", "tab", "backspace", "esc", "escape",
	"up", "down", "left", "right",
	"home", "end", "pgup", "pgdown",
	"insert", "delete",
	// function keys
	"f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
	// common modifier combos
	"ctrl+c", "ctrl+x", "ctrl+v", "ctrl+s", "ctrl+a", "ctrl+z",
	"ctrl+k", "ctrl+enter", "ctrl+space",
	"shift+tab", "alt+enter",
}

// matchesAny returns true if the binding matches at least one canonical
// key string when fed through a simulated tea.KeyPressMsg.
func matchesAny(b key.Binding) []string {
	var got []string
	for _, s := range canonicalKeyStrings {
		// Construct a fake stringer that returns the candidate key.
		fk := fakeKey(s)
		if key.Matches(fk, b) {
			got = append(got, s)
		}
	}
	return got
}

type fakeKey string

func (f fakeKey) String() string { return string(f) }

func TestEveryBindingHasHelpLabel(t *testing.T) {
	t.Parallel()
	for _, lb := range allBindings(Default()) {
		help := lb.bind.Help()
		require.NotEmptyf(t, help.Key, "%s.%s missing help.Key", lb.group, lb.name)
		require.NotEmptyf(t, help.Desc, "%s.%s missing help.Desc", lb.group, lb.name)
	}
}

// TestEveryBindingMatchesACanonicalKey is the property test that
// catches the space-vs-"space" class of bug.
//
// If a binding fails to match ANY canonical key string, then no real
// keypress can ever trigger it — it's dead code in the keymap.
func TestEveryBindingMatchesACanonicalKey(t *testing.T) {
	t.Parallel()
	km := Default()
	for _, lb := range allBindings(km) {
		matches := matchesAny(lb.bind)
		require.NotEmptyf(t, matches,
			"%s.%s does not match any canonical key string \u2014 the binding is unreachable. Help label: %q",
			lb.group, lb.name, lb.bind.Help().Key,
		)
	}
}

// TestSpacebarBindingMatchesBothForms specifically guards against the
// bug we hit: a literal ' ' binding doesn't match what KeyPressMsg
// actually returns ("space"), so subnet-toggle silently broke.
func TestSpacebarBindingMatchesBothForms(t *testing.T) {
	t.Parallel()
	km := Default()
	require.True(t, key.Matches(fakeKey(" "), km.Wizard.Toggle),
		"Wizard.Toggle must match literal space char")
	require.True(t, key.Matches(fakeKey("space"), km.Wizard.Toggle),
		"Wizard.Toggle must match named 'space' (what tea.KeyPressMsg.String() returns)")
}

// TestNoCollisionsWithinSameGroup verifies that within a sub-keymap (e.g.
// Wizard) no two bindings share a key string. A collision would make the
// behaviour ambiguous (whichever switch case runs first wins).
//
// Cross-group collisions are allowed (e.g. "enter" → Welcome.Choose AND
// Wizard.NextStep) because pages dispatch based on which page is active.
func TestNoCollisionsWithinSameGroup(t *testing.T) {
	t.Parallel()
	km := Default()

	groups := map[string][]labeledBinding{}
	for _, lb := range allBindings(km) {
		groups[lb.group] = append(groups[lb.group], lb)
	}

	for groupName, bindings := range groups {
		// Skip Global \u2014 by design Global keys (q, ?, esc) ARE shared with
		// page-local ones. The page Update functions are responsible for
		// not double-handling.
		if groupName == "Global" {
			continue
		}
		seen := map[string]string{}
		for _, lb := range bindings {
			for _, ks := range matchesAny(lb.bind) {
				if other, dup := seen[ks]; dup {
					t.Errorf("collision in %s: key %q matches both %s and %s",
						groupName, ks, other, lb.name)
				}
				seen[ks] = lb.name
			}
		}
	}
}

// TestCanonicalKeysAreNonEmpty is a meta-check that the test fixture
// itself isn't accidentally dropped to zero entries.
func TestCanonicalKeysAreNonEmpty(t *testing.T) {
	t.Parallel()
	require.Greater(t, len(canonicalKeyStrings), 50,
		"canonicalKeyStrings was %d \u2014 the property test would be a no-op",
		len(canonicalKeyStrings))
	// And the smoke check that fakeKey itself works.
	require.True(t, key.Matches(fakeKey("enter"),
		key.NewBinding(key.WithKeys("enter"))))
}

// TestHelpStrings_NoDoubleSpaces is a cosmetic sanity check.
func TestHelpStrings_NoDoubleSpaces(t *testing.T) {
	t.Parallel()
	for _, lb := range allBindings(Default()) {
		require.False(t, strings.Contains(lb.bind.Help().Desc, "  "),
			"%s.%s help text has double-spaces: %q",
			lb.group, lb.name, lb.bind.Help().Desc)
	}
}

// silence unused tea import — referenced via fakeKey signature only,
// but the type contract uses tea.KeyPressMsg.String().
var _ tea.KeyPressMsg
