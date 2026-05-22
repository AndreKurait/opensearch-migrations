package styles

import "image/color"

// Palette holds the eight semantic color tokens every theme MUST define.
//
// We don't accept theme files — themes are Go constructors so a missing
// token is a compile error rather than a render-time NPE.
type Palette struct {
	Bg, BgAlt color.Color // primary and elevated background
	Fg, FgMuted color.Color // primary and de-emphasized text
	Accent  color.Color // headers, active selections
	Danger  color.Color // errors, destructive actions
	Warn    color.Color // soft warnings (yellow/amber)
	Success color.Color // confirmations, ✓ marks
	Border  color.Color // panels, dialog borders
}
