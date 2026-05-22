package ui_test

import (
	"context"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/msg"
)

// driver is a minimal harness that drives a tea.Model via direct
// Update() calls and drains tea.Cmds synchronously. teatest/v2 in this
// codebase has a render-flush quirk: intermediate frames don't reach the
// output stream until tm.Quit() forces a flush. That's not the
// state-machine bug — it's a rendering layer concern. Direct-Update
// drives the same state transitions deterministically and 1000× faster.
type driver struct {
	t *testing.T
	m tea.Model
}

func newDriver(t *testing.T, m tea.Model) *driver {
	t.Helper()
	return &driver{t: t, m: m}
}

// Init runs the initial cmd and drains all messages.
func (d *driver) Init() {
	if init, ok := d.m.(interface{ Init() tea.Cmd }); ok {
		d.flush(init.Init())
	}
}

// Send delivers a message and drains any cmd batch.
func (d *driver) Send(msg tea.Msg) {
	d.t.Helper()
	var cmd tea.Cmd
	d.m, cmd = d.m.Update(msg)
	d.flush(cmd)
}

// flush executes the cmd and recursively delivers each resulting msg
// back into the model. Bounded to prevent runaway loops. Each cmd runs
// with a short deadline so blocking cmds (waitForEvent on a channel)
// don't deadlock the test harness.
func (d *driver) flush(cmd tea.Cmd) {
	d.t.Helper()
	const maxIter = 100
	work := []tea.Cmd{cmd}
	for i := 0; i < maxIter && len(work) > 0; i++ {
		c := work[0]
		work = work[1:]
		if c == nil {
			continue
		}
		out, ok := runCmdWithTimeout(c, 25*time.Millisecond)
		if !ok {
			// Blocked cmd — typically waitForEvent. Skip it.
			continue
		}
		if out == nil {
			continue
		}
		if _, isShutdown := out.(msg.ShutdownMsg); isShutdown {
			continue
		}
		if cmds, ok := out.(tea.BatchMsg); ok {
			for _, c := range cmds {
				work = append(work, c)
			}
			continue
		}
		var nc tea.Cmd
		d.m, nc = d.m.Update(out)
		if nc != nil {
			work = append(work, nc)
		}
	}
}

// runCmdWithTimeout calls cmd in a goroutine and returns the produced
// message, or (nil, false) if the deadline expires before cmd returns.
// The runaway goroutine is leaked deliberately — it'll exit when its
// channel either closes or produces.
func runCmdWithTimeout(cmd tea.Cmd, d time.Duration) (tea.Msg, bool) {
	ch := make(chan tea.Msg, 1)
	go func() {
		defer func() { _ = recover() }() // best-effort
		ch <- cmd()
	}()
	select {
	case out := <-ch:
		return out, true
	case <-time.After(d):
		return nil, false
	}
}

// View returns the current rendered string with ANSI escapes stripped.
func (d *driver) View() string {
	v := d.m.(interface{ View() tea.View }).View()
	return stripANSI(v.Content)
}

// TestE2E_FullHappyPath_Manual drives a fully mocked launch through the
// state machine: welcome → intent → wizard → review.
func TestE2E_FullHappyPath_Manual(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.AWSSvc = &testutil.FakeAWS{
		Identity: feature.AWSIdentity{
			AccountID: "123456789012",
			UserARN:   "arn:aws:iam::123456789012:user/Admin",
			Region:    "us-east-1",
		},
	}
	ws.AgSvc = &testutil.FakeAgents{Result: []feature.AgentCLI{
		{Name: "claude-code", Path: "/usr/local/bin/claude", LocalVersion: "2.1.140", LatestVersion: "2.1.140", BehindBy: feature.DeltaNone},
	}}

	d := newDriver(t, ui.New(ws))
	d.Init()

	// 1. Welcome page renders with detection.
	out := d.View()
	require.Contains(t, out, "OpenSearch Migration Assistant CLI")
	require.Contains(t, out, "123456789012")
	require.Contains(t, out, "claude-code")

	// 2. Press enter → navigate to intent.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Contains(t, d.View(), "What are you migrating?")

	// 3. Skip intent.
	d.Send(tea.KeyPressMsg{Code: 'k', Mod: tea.ModCtrl})
	require.Contains(t, d.View(), "Setup")

	// 4. Press enter through every wizard step. With default
	// scope=create-vpc the VPC/Subnets/Endpoints steps are skipped, so 8
	// enters complete the wizard.
	for i := 0; i < 8; i++ {
		d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	}

	// 5. Review screen shows equivalent argv with our defaults.
	rev := d.View()
	require.Contains(t, rev, "aws-bootstrap.sh")
	require.Contains(t, rev, "--stack-name MA-Dev")
	require.Contains(t, rev, "--tls-mode self-signed")
	require.Contains(t, rev, "--deploy-create-vpc-cfn")
}

// TestE2E_HandoffManualSetsExecTarget drives all the way to handoff and
// asserts the FakeWS captured the kubectl exec target.
func TestE2E_HandoffManualSetsExecTarget(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.AWSSvc = &testutil.FakeAWS{
		Identity: feature.AWSIdentity{AccountID: "1", UserARN: "arn:x", Region: "us-east-1"},
	}

	d := newDriver(t, ui.New(ws))
	d.Init()

	// Welcome → Intent → Wizard → Review → Deploy → Handoff. Wizard is
	// 8 enters with default create-vpc scope.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})  // welcome → intent
	d.Send(tea.KeyPressMsg{Code: 'k', Mod: tea.ModCtrl}) // intent skip
	for i := 0; i < 8; i++ {
		d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	// Review → Launch.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})

	// Skip the deploy phase by directly navigating.
	d.Send(msg.NavigateMsg{To: msg.PageHandoff})

	// Press enter on handoff → SetHandoff fires.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})

	require.Equal(t, "kubectl", ws.HandoffBin, "manual mode should exec kubectl")
	require.Contains(t, strings.Join(ws.HandoffArgs, " "), "exec -it migration-console-0")
}

// TestE2E_HandoffAgent verifies the agent path emits a kiro-cli exec target.
func TestE2E_HandoffAgent(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.AWSSvc = &testutil.FakeAWS{
		Identity: feature.AWSIdentity{AccountID: "1", UserARN: "arn:x", Region: "us-east-1"},
	}
	d := newDriver(t, ui.New(ws))
	d.Init()
	// Choose AI Agent (cursor=1) before pressing enter on welcome.
	d.Send(tea.KeyPressMsg{Code: tea.KeyDown})
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	d.Send(tea.KeyPressMsg{Code: 'k', Mod: tea.ModCtrl})
	for i := 0; i < 8; i++ {
		d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter}) // launch
	d.Send(msg.NavigateMsg{To: msg.PageHandoff})
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})

	require.Equal(t, "kiro-cli", ws.HandoffBin, "agent mode should exec kiro-cli")
	require.Contains(t, strings.Join(ws.HandoffArgs, " "), "@start")
}

// TestE2E_AWSFailureRendersGracefully verifies missing credentials don't
// crash the welcome screen — pages must continue to render with an error line.
func TestE2E_AWSFailureRendersGracefully(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	ws.AWSSvc = &testutil.FakeAWS{IdentityErr: context.DeadlineExceeded}

	d := newDriver(t, ui.New(ws))
	d.Init()
	out := d.View()
	require.Contains(t, out, "OpenSearch Migration Assistant CLI")
	require.Contains(t, out, "AWS identity   error")
	require.Contains(t, out, "context deadline exceeded")
}

// TestE2E_NavigateBackAndForward exercises the Back / Confirm path
// transitions to ensure pages remember state across navigation.
func TestE2E_NavigateBackAndForward(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	d := newDriver(t, ui.New(ws))
	d.Init()

	// Forward through welcome → intent.
	d.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Contains(t, d.View(), "What are you migrating?")

	// Back via NavigateMsg (esc handling on the intent page is owned by
	// the textinput when focused; simulate via direct nav).
	d.Send(msg.NavigateMsg{To: msg.PageWelcome})
	require.Contains(t, d.View(), "OpenSearch Migration Assistant CLI")
}

// TestE2E_StatusBarShowsLastMessage proves status routing.
func TestE2E_StatusBarShowsLastMessage(t *testing.T) {
	t.Parallel()
	ws := testutil.NewFakeWS(t)
	d := newDriver(t, ui.New(ws))
	d.Init()

	d.Send(msg.StatusMsg{Text: "saved session prod-us-east-1.json", TTL: 5 * time.Second})
	require.Contains(t, d.View(), "saved session prod-us-east-1.json")
}
