// Package styles centralizes every lipgloss.Style the UI uses.
//
// PLAN §10:
//   - palette.go: semantic tokens (Bg, Fg, Accent, Danger, …)
//   - styles.go:  the nested Styles struct of lipgloss.Style values
//   - themes.go:  Dark()/Light()/HighContrast() constructor functions
//
// Pages NEVER call lipgloss.NewStyle() inline — they read c.Styles.X.Y.
// This is what lets us re-theme the entire app with one constructor swap.
package styles
