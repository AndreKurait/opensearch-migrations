package styles

import lipgloss "charm.land/lipgloss/v2"

// Styles is the full nested catalog of lipgloss.Style values used by
// every page and component. Constructed once per theme via From(palette).
//
// To add a new style, declare the field here, populate it in From(), and
// reference it from the page. Avoids the "style sprawl" Crush warns
// against — every visual token lands in one file.
type Styles struct {
	Header struct {
		Title     lipgloss.Style
		Subtle    lipgloss.Style
		Tab       lipgloss.Style
		ActiveTab lipgloss.Style
	}
	Footer struct {
		Bar  lipgloss.Style
		Hint lipgloss.Style
	}
	List struct {
		Item     lipgloss.Style
		Selected lipgloss.Style
		Disabled lipgloss.Style
		Marker   lipgloss.Style
	}
	Form struct {
		Label    lipgloss.Style
		Input    lipgloss.Style
		Help     lipgloss.Style
		Error    lipgloss.Style
		Required lipgloss.Style
	}
	Dialog struct {
		Border lipgloss.Style
		Title  lipgloss.Style
		Body   lipgloss.Style
	}
	Status struct {
		Info    lipgloss.Style
		Warn    lipgloss.Style
		Error   lipgloss.Style
		Success lipgloss.Style
	}
	Page struct {
		Container lipgloss.Style
		Section   lipgloss.Style
		Hint      lipgloss.Style
	}
	Code lipgloss.Style
}

// From returns a Styles value populated from the given palette. Pure: no
// global state, no Renderer (lipgloss v2 — PLAN §5).
func From(p Palette) *Styles {
	s := &Styles{}

	s.Header.Title = lipgloss.NewStyle().Bold(true).Foreground(p.Accent)
	s.Header.Subtle = lipgloss.NewStyle().Foreground(p.FgMuted)
	s.Header.Tab = lipgloss.NewStyle().Foreground(p.FgMuted).Padding(0, 1)
	s.Header.ActiveTab = lipgloss.NewStyle().Foreground(p.Accent).Bold(true).Padding(0, 1)

	s.Footer.Bar = lipgloss.NewStyle().Foreground(p.FgMuted)
	s.Footer.Hint = lipgloss.NewStyle().Foreground(p.FgMuted).Italic(true)

	s.List.Item = lipgloss.NewStyle().Foreground(p.Fg)
	s.List.Selected = lipgloss.NewStyle().Foreground(p.Accent).Bold(true)
	s.List.Disabled = lipgloss.NewStyle().Foreground(p.FgMuted).Faint(true)
	s.List.Marker = lipgloss.NewStyle().Foreground(p.Accent)

	s.Form.Label = lipgloss.NewStyle().Foreground(p.Fg).Bold(true)
	s.Form.Input = lipgloss.NewStyle().Foreground(p.Fg).Background(p.BgAlt)
	s.Form.Help = lipgloss.NewStyle().Foreground(p.FgMuted).Faint(true)
	s.Form.Error = lipgloss.NewStyle().Foreground(p.Danger)
	s.Form.Required = lipgloss.NewStyle().Foreground(p.Warn)

	s.Dialog.Border = lipgloss.NewStyle().BorderStyle(lipgloss.RoundedBorder()).BorderForeground(p.Border).Padding(1, 2)
	s.Dialog.Title = lipgloss.NewStyle().Bold(true).Foreground(p.Accent)
	s.Dialog.Body = lipgloss.NewStyle().Foreground(p.Fg)

	s.Status.Info = lipgloss.NewStyle().Foreground(p.Fg)
	s.Status.Warn = lipgloss.NewStyle().Foreground(p.Warn)
	s.Status.Error = lipgloss.NewStyle().Foreground(p.Danger).Bold(true)
	s.Status.Success = lipgloss.NewStyle().Foreground(p.Success)

	s.Page.Container = lipgloss.NewStyle().Padding(1, 2)
	s.Page.Section = lipgloss.NewStyle().MarginBottom(1)
	s.Page.Hint = lipgloss.NewStyle().Foreground(p.FgMuted).Italic(true)

	s.Code = lipgloss.NewStyle().Foreground(p.Fg).Background(p.BgAlt).Padding(0, 1)

	return s
}
