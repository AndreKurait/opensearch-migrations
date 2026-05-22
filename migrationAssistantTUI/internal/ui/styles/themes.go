package styles

import lipgloss "charm.land/lipgloss/v2"

// Dark is the default theme.
func Dark() *Styles {
	return From(Palette{
		Bg:      lipgloss.Color("#0b0b0f"),
		BgAlt:   lipgloss.Color("#1a1a22"),
		Fg:      lipgloss.Color("#e8e8f0"),
		FgMuted: lipgloss.Color("#7a7a8a"),
		Accent:  lipgloss.Color("#5af0c8"),
		Danger:  lipgloss.Color("#ff5c7a"),
		Warn:    lipgloss.Color("#ffc857"),
		Success: lipgloss.Color("#7af07a"),
		Border:  lipgloss.Color("#2a2a35"),
	})
}

// Light flips the bg/fg pair; accent stays distinguishable.
func Light() *Styles {
	return From(Palette{
		Bg:      lipgloss.Color("#fdfdfd"),
		BgAlt:   lipgloss.Color("#eeeef2"),
		Fg:      lipgloss.Color("#101015"),
		FgMuted: lipgloss.Color("#6a6a78"),
		Accent:  lipgloss.Color("#1e7a5c"),
		Danger:  lipgloss.Color("#c8203a"),
		Warn:    lipgloss.Color("#a06800"),
		Success: lipgloss.Color("#1d6a1d"),
		Border:  lipgloss.Color("#c8c8d0"),
	})
}

// HighContrast for accessibility; pure white-on-black, no styled muted text.
func HighContrast() *Styles {
	return From(Palette{
		Bg:      lipgloss.Color("#000000"),
		BgAlt:   lipgloss.Color("#000000"),
		Fg:      lipgloss.Color("#ffffff"),
		FgMuted: lipgloss.Color("#dddddd"),
		Accent:  lipgloss.Color("#ffff00"),
		Danger:  lipgloss.Color("#ff5555"),
		Warn:    lipgloss.Color("#ffaa00"),
		Success: lipgloss.Color("#55ff55"),
		Border:  lipgloss.Color("#ffffff"),
	})
}

// ByName returns a theme by user-facing name; defaults to Dark.
func ByName(name string) *Styles {
	switch name {
	case "light":
		return Light()
	case "hi-contrast", "high-contrast":
		return HighContrast()
	default:
		return Dark()
	}
}
