// Package keys is the single source of truth for keybindings AND help text.
//
// PLAN §8: every binding lives in this struct, carries help via
// key.WithHelp, and the help screen consumes the same struct. Pages get
// per-page sub-structs so root and pages share Global keys without
// duplication.
package keys

import key "charm.land/bubbles/v2/key"

// KeyMap is the global binding catalog.
type KeyMap struct {
	Global struct {
		Quit       key.Binding
		Help       key.Binding
		Back       key.Binding
		Confirm    key.Binding
		SwitchMode key.Binding
		SaveExit   key.Binding
		SwitchAcct key.Binding
	}
	Welcome struct {
		Up, Down, Choose, Refresh, SwitchProfile key.Binding
	}
	Intent struct {
		NextField, PrevField, Skip, Continue key.Binding
	}
	Wizard struct {
		Up, Down, Filter, Manual, Toggle, NextStep, PrevStep, Info key.Binding
	}
	Review struct {
		Launch, CopyCmd, Details key.Binding
	}
	Deploy struct {
		Background, FullLog key.Binding
	}
	Handoff struct {
		Open, ShowCmd key.Binding
	}
}

// Default returns the production keymap. Help text is the second arg of
// key.NewBinding(WithKeys("…"), WithHelp("k", "label")) — that label is
// what `bubbles/help` renders in the help overlay.
func Default() KeyMap {
	var k KeyMap

	// Global
	k.Global.Quit = key.NewBinding(key.WithKeys("q", "ctrl+c"), key.WithHelp("q", "quit"))
	k.Global.Help = key.NewBinding(key.WithKeys("?"), key.WithHelp("?", "help"))
	k.Global.Back = key.NewBinding(key.WithKeys("esc", "b"), key.WithHelp("esc/b", "back"))
	k.Global.Confirm = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "confirm"))
	k.Global.SwitchMode = key.NewBinding(key.WithKeys("m"), key.WithHelp("m", "switch mode"))
	k.Global.SaveExit = key.NewBinding(key.WithKeys("s"), key.WithHelp("s", "save & exit"))
	k.Global.SwitchAcct = key.NewBinding(key.WithKeys("a"), key.WithHelp("a", "switch AWS"))

	// Welcome
	k.Welcome.Up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "up"))
	k.Welcome.Down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "down"))
	k.Welcome.Choose = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "choose"))
	k.Welcome.Refresh = key.NewBinding(key.WithKeys("r"), key.WithHelp("r", "refresh detection"))
	k.Welcome.SwitchProfile = key.NewBinding(key.WithKeys("a"), key.WithHelp("a", "switch AWS profile"))

	// Intent
	k.Intent.NextField = key.NewBinding(key.WithKeys("tab", "down"), key.WithHelp("tab", "next"))
	k.Intent.PrevField = key.NewBinding(key.WithKeys("shift+tab", "up"), key.WithHelp("shift+tab", "prev"))
	k.Intent.Skip = key.NewBinding(key.WithKeys("k"), key.WithHelp("k", "skip"))
	k.Intent.Continue = key.NewBinding(key.WithKeys("ctrl+enter", "ctrl+s"), key.WithHelp("ctrl+s", "continue"))

	// Wizard
	k.Wizard.Up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "up"))
	k.Wizard.Down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "down"))
	k.Wizard.Filter = key.NewBinding(key.WithKeys("/"), key.WithHelp("/", "filter"))
	k.Wizard.Manual = key.NewBinding(key.WithKeys("m"), key.WithHelp("m", "manual entry"))
	k.Wizard.Toggle = key.NewBinding(key.WithKeys("space", " "), key.WithHelp("space", "toggle"))
	k.Wizard.NextStep = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "next"))
	k.Wizard.PrevStep = key.NewBinding(key.WithKeys("esc"), key.WithHelp("esc", "prev"))
	k.Wizard.Info = key.NewBinding(key.WithKeys("f1"), key.WithHelp("f1", "info"))

	// Review
	k.Review.Launch = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "launch"))
	k.Review.CopyCmd = key.NewBinding(key.WithKeys("c"), key.WithHelp("c", "copy command"))
	k.Review.Details = key.NewBinding(key.WithKeys("d"), key.WithHelp("d", "cost details"))

	// Deploy
	k.Deploy.Background = key.NewBinding(key.WithKeys("esc"), key.WithHelp("esc", "background"))
	k.Deploy.FullLog = key.NewBinding(key.WithKeys("l"), key.WithHelp("l", "full log"))

	// Handoff
	k.Handoff.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "open now"))
	k.Handoff.ShowCmd = key.NewBinding(key.WithKeys("c"), key.WithHelp("c", "show command"))

	return k
}
