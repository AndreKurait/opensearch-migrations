// Package workspace defines the façade the UI uses to talk to services.
//
// Per PLAN §3 rule 1: internal/ui does no I/O. It calls Workspace methods
// instead, which return tea.Cmd factories or domain values. Tests inject
// a fake (workspace.Fake) — the entire UI surface is testable without
// touching AWS, helm, or the network.
package workspace

import (
	"context"
	"log/slog"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/config"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

// Workspace is the only type internal/ui imports from internal/app.
//
// All methods are safe to call from Update; the AWS/Helm/Agent calls
// they front are async (broker-published) — see PLAN §4.3.
type Workspace interface {
	// Config returns the loaded user config. Read-only.
	Config() config.Config

	// Logger returns the slog logger. Provided so pages don't import slog directly.
	Logger() *slog.Logger

	// Events returns the broker-subscribed message stream. The UI's
	// `waitForEvent` cmd consumes this; closes when ctx ends OR app shuts down.
	Events() <-chan tea.Msg

	// AWS returns the AWS service, or nil if unavailable (no credentials,
	// for example). Pages that need AWS gracefully degrade to manual entry
	// per UX.md §6.3.
	AWS() feature.AWSService

	// Helm returns the helm client, or nil if no kubeconfig has been resolved yet.
	Helm() feature.HelmService

	// Agents returns the agent-CLI detector. Always non-nil.
	Agents() feature.AgentDetector

	// Artifacts returns the release-asset / raw-repo fetcher. Always non-nil.
	Artifacts() feature.ArtifactSource

	// Cwd is the directory the TUI was launched from. Used for the
	// named-subdir workdir guards (UX.md §0.4).
	Cwd() string

	// Version is the running TUI version (e.g. "1.0.0"). Pages display
	// it on the welcome screen and write it into HANDOFF.md.
	Version() string

	// SetHandoff records the post-TUI exec target. Called by the
	// handoff page right before tea.Quit.
	SetHandoff(bin string, args []string, cwd string)

	// SetHandoffWithPre records the post-TUI exec target plus a list of
	// commands to run before the final exec. Used to set up the kubectl
	// context (`aws eks update-kubeconfig`) before kubectl exec.
	SetHandoffWithPre(bin string, args []string, cwd string, preBin string, preArgs []string, preDesc string)

	// Workdir returns the resolved named-subdir workspace path (e.g.
	// ./opensearch-migration-123-us-east-1) or "" if not yet ready.
	// Pages use this to render and exec into the right place.
	Workdir() string

	// AgentBin returns the resolved binary name for the user's chosen
	// agent CLI (kiro-cli or claude). Returns "" when the agent isn't
	// installed; the handoff page falls back to a clear error in that case.
	AgentBin() string

	// DeployDriver returns the orchestrator that runs the real CFN +
	// helm install flow per aws-bootstrap.sh 3.2.1. Returns nil when AWS
	// is unavailable; the deploy page falls back to a simulated tick in
	// that case.
	DeployDriver() *DeployDriverProxy
}

// DeployDriverProxy is a thin façade that lets the UI start a real
// deploy run without importing the heavy aws-sdk-v2 deploy package.
// Implementations live in app/.
type DeployDriverProxy struct {
	Start func(ctx context.Context, params DeployParams, events chan<- DeployEvent) error
}

// DeployParams mirrors feature/deploy.Params at the workspace boundary.
type DeployParams struct {
	Region        string
	StackName     string
	Stage         string
	Scope         string
	VPCID         string
	SubnetIDs     []string
	VPCEndpoints  []string
	MAVersion     string
	TLSMode       string
	PCAARN        string
	Namespace     string
	ReleaseName   string
	EKSAccessARNs []string
	TemplateBody  string
	HelmChartPath string
	CallerARN     string
}

// DeployEvent mirrors feature/deploy.PhaseEvent at this layer.
type DeployEvent struct {
	Phase, Status, Message string
	At                     time.Time
}
