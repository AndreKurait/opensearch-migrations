// Package msg holds cross-cutting tea.Msg types: navigation, errors,
// status updates. Page-private msgs live with the page.
package msg

import (
	"time"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

// PageID is a typed page identifier used by NavigateMsg.
type PageID int

const (
	PageWelcome PageID = iota
	PageIntent
	PageWizard
	PageReview
	PageDeploy
	PageHandoff
	PageResume
	PageInstalled
)

func (p PageID) String() string {
	switch p {
	case PageWelcome:
		return "welcome"
	case PageIntent:
		return "intent"
	case PageWizard:
		return "wizard"
	case PageReview:
		return "review"
	case PageDeploy:
		return "deploy"
	case PageHandoff:
		return "handoff"
	case PageResume:
		return "resume"
	case PageInstalled:
		return "installed"
	default:
		return "unknown"
	}
}

// NavigateMsg requests a page transition. Root model handles it.
type NavigateMsg struct{ To PageID }

// Sev classifies an error for routing (PLAN §7.2).
type Sev int

const (
	SevToast Sev = iota
	SevStatus
	SevModal
)

// ErrorMsg carries one user-visible error. Routed by severity:
// SevToast → top-right ephemeral; SevStatus → status bar;
// SevModal → blocking dialog.
type ErrorMsg struct {
	Err      error
	Severity Sev
	Title    string
	TTL      time.Duration
}

// StatusMsg is a non-error informational status (e.g. "Saved").
type StatusMsg struct {
	Text string
	TTL  time.Duration
}

// LayoutMsg is fired by root after a WindowSizeMsg, with the rect each
// child page should render into. Pages don't subscribe to WindowSizeMsg
// directly — keeps testing simpler (PLAN appendix A).
type LayoutMsg struct {
	Width, Height int
}

// MAReleaseDetectedMsg is emitted once we've fetched the latest MA
// release tag from GitHub. Best-effort: Err non-nil means we'll fall
// back to the pinned default.
type MAReleaseDetectedMsg struct {
	LatestTag string
	PinnedTag string
	Err       error
}

// TUIVersionDetectedMsg is emitted once we've checked for a newer TUI
// release. Best-effort — GitHub unreachable means SkippedByUser=false
// and Delta="unknown".
type TUIVersionDetectedMsg struct {
	Local         string
	Latest        string
	Delta         string // "" | "patch" | "minor" | "major" | "ahead" | "unknown"
	SkippedByUser bool
}

// AWSDetectedMsg is emitted once the launch-time AWS detection finishes.
type AWSDetectedMsg struct {
	Identity feature.AWSIdentity
	MAExports []feature.CFNExport
	Err      error
}

// WorkdirDetectedMsg is emitted once we've inspected the cwd's named
// subdir for a prior install. The presence of a state file means the
// previous launch reached at least the install step; offer resume.
type WorkdirDetectedMsg struct {
	Workdir string // resolved workdir path (e.g. ./opensearch-migration-123-us-east-1)
	Has     bool   // true when a .ma-state.json exists
	Stage   string // stage from state file, when applicable
	Status  string // "in_progress" | "installed" | "failed" | ""
	Err     error
}

// VPCsDetectedMsg lands when describe-vpcs finishes for the active region.
type VPCsDetectedMsg struct {
	VPCs []feature.VPC
	Err  error
}

// SubnetsDetectedMsg lands when describe-subnets finishes for the
// chosen VPC.
type SubnetsDetectedMsg struct {
	Subnets []feature.Subnet
	Err     error
}

// VPCEndpointsDetectedMsg lands when describe-vpc-endpoints finishes
// for the chosen VPC.
type VPCEndpointsDetectedMsg struct {
	Endpoints []feature.VPCEndpoint
	Err       error
}

// AgentsDetectedMsg lands once §0.6 agent-CLI detection completes.
type AgentsDetectedMsg struct {
	Agents []feature.AgentCLI
	Err    error
}

// ToolsDetectedMsg lands once required-CLI detection (kubectl/helm/aws/git) completes.
type ToolsDetectedMsg struct {
	Found map[string]string // name -> path
}

// ShutdownMsg is emitted when the broker channel closes (PLAN §4.3).
type ShutdownMsg struct{}

// ToastClearMsg is sent by a tea.Tick to retract a toast.
type ToastClearMsg struct{ ID uint64 }
