package ui_test

import (
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/x/exp/golden"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
)

// TestGolden_Welcome_Loaded asserts the welcome page renders deterministically
// once detection has completed.
//
// Per PLAN §11.3, golden snapshots live ONLY at page-level major states
// to keep the testdata budget under 1MB.
func TestGolden_Welcome_Loaded(t *testing.T) {
	ws := testutil.NewFakeWS(t)
	m := ui.New(ws)
	// Drive page to a deterministic state.
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	m.Update(msg.LayoutMsg{Width: 100, Height: 30})
	m.Update(msg.AWSDetectedMsg{
		Identity: feature.AWSIdentity{
			AccountID: "123456789012",
			UserARN:   "arn:aws:iam::123456789012:user/Admin",
			Region:    "us-east-1",
		},
	})
	m.Update(msg.AgentsDetectedMsg{Agents: []feature.AgentCLI{
		{Name: "claude-code", Path: "/usr/local/bin/claude", LocalVersion: "2.1.140", LatestVersion: "2.1.147", BehindBy: feature.DeltaPatch},
		{Name: "kiro-cli", Path: "/usr/local/bin/kiro-cli", LocalVersion: "0.4.1"},
	}})
	m.Update(msg.ToolsDetectedMsg{Tools: []feature.Tool{
		{Name: "helm", Path: "/usr/local/bin/helm", Version: "v3.15.0", Required: true, Installable: true},
		{Name: "kubectl", Path: "/usr/local/bin/kubectl", Version: "v1.31.0", Required: true},
		{Name: "aws", Path: "/usr/local/bin/aws", Version: "2.17.0", Required: true},
		{Name: "docker", Path: "/usr/local/bin/docker", Version: "27.1.0", Required: false},
	}})
	out := stripANSI(m.View().Content)
	golden.RequireEqual(t, []byte(out))
}

// TestE2E_LaunchAndQuit runs a real tea.Program against in-memory pipes
// and asserts the welcome screen renders, then sends q to quit.
func TestE2E_LaunchAndQuit(t *testing.T) {
	ws := testutil.NewFakeWS(t)
	tm := testutil.NewHarness(t, ui.New(ws))
	testutil.WaitForOutput(t, tm, "OpenSearch Migration Assistant CLI", 2*time.Second)
	tm.Send(tea.KeyPressMsg{Code: 'q', Text: "q"})
	_ = testutil.FinalOutput(t, tm, time.Second)
}

// stripANSI removes ANSI escape sequences for stable goldens. Mirrors
// what `colorprofile.Ascii` does at the program layer; we use this for
// direct .View() calls outside teatest.
func stripANSI(s string) string {
	var b strings.Builder
	inEsc := false
	for _, r := range s {
		if inEsc {
			if r == 'm' || (r >= '@' && r <= '~') {
				inEsc = false
			}
			continue
		}
		if r == 0x1b { // ESC
			inEsc = true
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}
