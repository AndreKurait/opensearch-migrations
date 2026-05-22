package testutil

import (
	"bytes"
	"io"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	teatest "github.com/charmbracelet/x/exp/teatest/v2"
)

// NewHarness wraps teatest.NewTestModel with project defaults: 100×30
// terminal, no real signals. Tests can layer extra options on top.
func NewHarness(t *testing.T, m tea.Model, opts ...teatest.TestOption) *teatest.TestModel {
	t.Helper()
	defaults := []teatest.TestOption{
		teatest.WithInitialTermSize(100, 30),
	}
	defaults = append(defaults, opts...)
	tm := teatest.NewTestModel(t, m, defaults...)
	t.Cleanup(func() {
		if err := tm.Quit(); err != nil {
			t.Logf("teatest quit: %v", err)
		}
	})
	return tm
}

// WaitForOutput blocks until `needle` appears in the program's output or
// `within` elapses (then fails the test).
func WaitForOutput(t *testing.T, tm *teatest.TestModel, needle string, within time.Duration) {
	t.Helper()
	teatest.WaitFor(t, tm.Output(), func(b []byte) bool {
		return bytes.Contains(b, []byte(needle))
	}, teatest.WithDuration(within))
}

// FinalOutput drains the program to completion and returns its full output.
func FinalOutput(t *testing.T, tm *teatest.TestModel, within time.Duration) []byte {
	t.Helper()
	r := tm.FinalOutput(t, teatest.WithFinalTimeout(within))
	out, _ := io.ReadAll(r)
	return out
}
