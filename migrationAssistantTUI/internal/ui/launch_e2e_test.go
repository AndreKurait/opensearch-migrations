package ui_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
)

// TestLaunchMsgFlowsToDeployPage proves the LaunchMsg → deploy page
// pipeline is wired end-to-end. With no DeployDriver, root must emit a
// red "AWS not configured" failure event that surfaces in the deploy
// page's view as "Deploy failed:".
//
// Catches the silent-spinner bug class: the deploy page must NEVER sit
// at PENDING with no progress when no driver is wired.
func TestLaunchMsgFlowsToDeployPage(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	// Set AWS service so the wizard can complete; do NOT set Driver \u2014
	// that triggers the explicit-failure code path.
	ws.AWSSvc = &testutil.FakeAWS{
		Identity: feature.AWSIdentity{AccountID: "1", UserARN: "arn:x", Region: "us-east-1"},
	}

	d := newDriver(t, ui.New(ws))
	d.Init()

	// Welcome \u2192 Intent \u2192 Wizard (8 enters with default scope) \u2192 Review.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	d.Send(tea.KeyPressMsg{Code: 'k', Mod: tea.ModCtrl})
	for i := 0; i < 8; i++ {
		d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	}

	// Now on Review \u2014 press enter to launch.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})

	// Force navigation to the deploy page so View() renders it.
	d.Send(msg.NavigateMsg{To: msg.PageDeploy})

	out := d.View()
	require.True(t, strings.Contains(out, "Deploy failed:"),
		"deploy page must show a failure when no driver is configured \u2014 silent spinners are a bug.\nGot:\n%s", out)
	require.True(t, strings.Contains(out, "AWS not configured"),
		"failure message must explain WHY \u2014 'AWS not configured \u2014 deploy aborted'.\nGot:\n%s", out)
}

// TestLaunchMsgWithDriverEmitsEvents proves the happy path: with a
// DeployDriver wired, LaunchMsg starts the goroutine and PhaseEvents
// flow through to the deploy page.
func TestLaunchMsgWithDriverEmitsEvents(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.AWSSvc = &testutil.FakeAWS{
		Identity: feature.AWSIdentity{AccountID: "1", UserARN: "arn:x", Region: "us-east-1"},
	}
	// Wire a driver that emits a known event sequence.
	ws.Driver = testutil.FakeDriver(func(events chan<- testutil.WSDeployEvent) error {
		events <- testutil.WSDeployEvent{Phase: "cfn", Status: "started", Message: "creating stack MA-Dev"}
		events <- testutil.WSDeployEvent{Phase: "cfn", Status: "progress", Message: "AWS::EC2::VPC CREATE_IN_PROGRESS"}
		return nil
	})

	d := newDriver(t, ui.New(ws))
	d.Init()
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	d.Send(tea.KeyPressMsg{Code: 'k', Mod: tea.ModCtrl})
	for i := 0; i < 8; i++ {
		d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter}) // Launch
	d.Send(msg.NavigateMsg{To: msg.PageDeploy})

	out := d.View()
	require.True(t, strings.Contains(out, "creating stack MA-Dev") || strings.Contains(out, "CREATE_IN_PROGRESS"),
		"deploy page must show a real driver event.\nGot:\n%s", out)
}
