// Package testutil holds the test harness: fake Workspace, fake services,
// teatest helpers. Importable only from _test.go files; never from
// production code.
package testutil

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/config"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/pubsub"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/workspace"
)

// WSDeployEvent re-exports the workspace.DeployEvent shape so test
// files don't need to import the workspace package directly.
type WSDeployEvent = workspace.DeployEvent

// FakeDriver returns a workspace.DeployDriverProxy whose Start fn calls
// the supplied producer in a goroutine. Closes the events channel when
// the producer returns. Safe for happy-path + failure tests.
func FakeDriver(producer func(events chan<- WSDeployEvent) error) *workspace.DeployDriverProxy {
	return &workspace.DeployDriverProxy{
		Start: func(ctx context.Context, params workspace.DeployParams, events chan<- workspace.DeployEvent) error {
			err := producer(events)
			close(events)
			return err
		},
	}
}

// FakeAWS is a hand-rolled stub. Set the result fields directly in tests.
type FakeAWS struct {
	Identity    feature.AWSIdentity
	IdentityErr error
	VPCs        []feature.VPC
	Subnets     []feature.Subnet
	Exports     []feature.CFNExport
	VPCEndpoints []feature.VPCEndpoint
}

func (f *FakeAWS) WhoAmI(ctx context.Context) (feature.AWSIdentity, error) {
	return f.Identity, f.IdentityErr
}
func (f *FakeAWS) ListVPCs(ctx context.Context, region string) ([]feature.VPC, error) {
	return f.VPCs, nil
}
func (f *FakeAWS) ListSubnets(ctx context.Context, region, vpcID string) ([]feature.Subnet, error) {
	return f.Subnets, nil
}
func (f *FakeAWS) ListMAExports(ctx context.Context, region string) ([]feature.CFNExport, error) {
	return f.Exports, nil
}
func (f *FakeAWS) ListVPCEndpoints(ctx context.Context, region, vpcID string) ([]feature.VPCEndpoint, error) {
	return f.VPCEndpoints, nil
}

// FakeAgents always returns the same set.
type FakeAgents struct{ Result []feature.AgentCLI }

func (f *FakeAgents) Detect(ctx context.Context) ([]feature.AgentCLI, error) {
	return f.Result, nil
}

// FakeArtifacts is a stub fetcher that creates a 1-byte placeholder
// file at every requested destination. Sufficient for test paths that
// only check "file exists / readable" semantics.
type FakeArtifacts struct{}

func (FakeArtifacts) FetchAtTag(ctx context.Context, tag, name, dst string) (string, error) {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", err
	}
	return "", os.WriteFile(dst, []byte("# fake artifact for tests\n"), 0o644)
}

// FakeWS is the in-test Workspace implementation.
type FakeWS struct {
	Cfg    config.Config
	AWSSvc feature.AWSService
	AgSvc  feature.AgentDetector
	ArtSvc feature.ArtifactSource
	WD      string
	Ver     string
	Broker  *pubsub.Broker[tea.Msg]

	HandoffBin  string
	HandoffArgs []string
	HandoffCwd  string
	HandoffPreBin  string
	HandoffPreArgs []string
	GoodbyeMsg     string

	Driver *workspace.DeployDriverProxy

	WorkdirPath string
	Agent       string

	logger *slog.Logger
}

// NewFakeWS returns a sane default fake — empty AWS, no agents, broker
// open. Tests mutate fields before passing it to ui.New.
//
// Cwd is t.TempDir() so tests can't pollute the source tree with the
// `opensearch-migration-<account>-<region>/` workspace dirs the UI
// auto-creates during AWS detection.
func NewFakeWS(t *testing.T) *FakeWS {
	t.Helper()
	br := pubsub.New[tea.Msg](16)
	t.Cleanup(br.Close)
	wd := safeTempDir(t)
	return &FakeWS{
		Cfg:    config.Default(),
		AgSvc:  &FakeAgents{},
		ArtSvc: FakeArtifacts{},
		WD:     wd,
		Ver:    "test-0.0.0",
		Broker: br,
		logger: slog.Default(),
	}
}

// safeTempDir wraps t.TempDir() to avoid the workdir deny-list
// (UX.md §0.4 forbids running from /var/*, which is exactly where
// macOS's t.TempDir() places things). Falls back to /tmp/<unique>
// when needed.
func safeTempDir(t *testing.T) string {
	t.Helper()
	// Prefer ~/.tui-test/<unique-per-test> so it's writable AND not on
	// the deny-list.
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return t.TempDir()
	}
	dir := filepath.Join(home, ".tui-test", strings.ReplaceAll(t.Name(), "/", "_"))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return t.TempDir()
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func (f *FakeWS) Config() config.Config           { return f.Cfg }
func (f *FakeWS) Logger() *slog.Logger             { return f.logger }
func (f *FakeWS) Events() <-chan tea.Msg           { return f.Broker.Subscribe(context.Background()) }
func (f *FakeWS) AWS() feature.AWSService          { return f.AWSSvc }
func (f *FakeWS) Agents() feature.AgentDetector    { return f.AgSvc }
func (f *FakeWS) Artifacts() feature.ArtifactSource { return f.ArtSvc }
func (f *FakeWS) Cwd() string                      { return f.WD }
func (f *FakeWS) Version() string                  { return f.Ver }
func (f *FakeWS) Workdir() string                  { return f.WorkdirPath }
func (f *FakeWS) AgentBin() string                 { return f.Agent }
func (f *FakeWS) DeployDriver() *workspace.DeployDriverProxy { return f.Driver }
func (f *FakeWS) SetHandoff(bin string, args []string, cwd string) {
	f.HandoffBin = bin
	f.HandoffArgs = args
	f.HandoffCwd = cwd
}
func (f *FakeWS) SetHandoffWithPre(bin string, args []string, cwd string, preBin string, preArgs []string, preDesc string) {
	f.HandoffBin = bin
	f.HandoffArgs = args
	f.HandoffCwd = cwd
	f.HandoffPreBin = preBin
	f.HandoffPreArgs = preArgs
}

// Goodbye is the most recent message recorded via SetGoodbye.
func (f *FakeWS) SetGoodbye(s string) { f.GoodbyeMsg = s }

// SetWorkdir records the resolved workdir for assertions.
func (f *FakeWS) SetWorkdir(s string) { f.WorkdirPath = s }

// SetAgentBin records the agent CLI name for assertions.
func (f *FakeWS) SetAgentBin(s string) { f.Agent = s }
