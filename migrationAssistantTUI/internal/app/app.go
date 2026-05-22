// Package app is the composition root: it wires services and the pubsub
// broker, exposes a Workspace to the UI, and owns the §9.2 shutdown
// lifecycle.
//
//	main → app.New(ctx) → tea.Program.Run() → defer app.Shutdown
//
// app.Shutdown does the §9.2 dance:
//
//	cancel(internal ctx) → wg.Wait() for publishers → broker.Close()
package app

import (
	"context"
	"log/slog"
	"os"
	"sync"

	tea "charm.land/bubbletea/v2"

	awssdk "github.com/aws/aws-sdk-go-v2/aws"
	cfnclient "github.com/aws/aws-sdk-go-v2/service/cloudformation"
	ec2client "github.com/aws/aws-sdk-go-v2/service/ec2"
	eksclient "github.com/aws/aws-sdk-go-v2/service/eks"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/config"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature/agents"
	awssvc "github.com/opensearch-project/opensearch-migrations/tui/internal/feature/aws"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature/artifacts"
	deployfeat "github.com/opensearch-project/opensearch-migrations/tui/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/pubsub"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/workspace"
)

// Config is the boot-time wiring config (NOT the persistent user config).
type Config struct {
	Version string
}

// App is the composition root.
//
// Concurrency: A single internal context cancels every publisher
// goroutine; serviceWG tracks them so Shutdown can wait before closing
// the broker (PLAN §9.2).
type App struct {
	cfg Config

	// internal ctx — cancel signals every publisher goroutine.
	ctx       context.Context
	cancel    context.CancelFunc
	serviceWG sync.WaitGroup

	events  *pubsub.Broker[tea.Msg]
	logger  *slog.Logger
	cfgFile config.Config

	// services
	aws       feature.AWSService     // nil OK — UI degrades
	agents    feature.AgentDetector  // never nil
	artifacts feature.ArtifactSource // never nil

	// AWS SDK config + clients (used by the deploy driver)
	awsCfg awssdk.Config
	cfn    *cfnclient.Client
	ec2    *ec2client.Client
	eks    *eksclient.Client

	cwd string

	once sync.Once // shutdown idempotence

	handoffMu sync.Mutex
	handoff   HandoffCommand
	goodbye   string

	// workdirMu/workdirPath are populated by SetWorkdir from the UI
	// after the named-subdir is resolved.
	workdirMu sync.Mutex
	workdirPath string

	// agentMu/agentBin track which agent CLI is on PATH. The handoff
	// page reads this when resolving the exec target.
	agentMu  sync.Mutex
	agentBin string
}

// HandoffCommand is the post-TUI exec target. Returns the empty value
// when no handoff was selected (e.g. user quit early).
type HandoffCommand struct {
	Bin  string
	Args []string
	Cwd  string

	// PreExec is an optional command to run BEFORE the syscall.Exec. Used
	// for `aws eks update-kubeconfig` so the kubectl context exists when
	// `kubectl exec` runs. Stdout/stderr stream to the user's terminal.
	PreExec []PreExecStep
}

// PreExecStep is one shell-out the post-TUI runtime should execute
// before the final syscall.Exec.
type PreExecStep struct {
	Bin  string
	Args []string
	// Description is shown to the user as a single line before the cmd runs.
	Description string
}

// New constructs an App. Service wiring failures are logged but never
// fatal — the UI must always launch so the user can at least see the
// detection summary and quit cleanly (UX.md §7).
func New(parent context.Context, cfg Config) (*App, error) {
	c, _, err := config.Load()
	if err != nil {
		// Non-fatal: missing/corrupt config falls back to defaults.
		slog.Warn("app.config_load_failed", "err", err)
	}

	cwd, _ := os.Getwd()
	if cwd == "" {
		cwd = "."
	}

	ctx, cancel := context.WithCancel(parent)
	a := &App{
		cfg:     cfg,
		ctx:     ctx,
		cancel:  cancel,
		events:  pubsub.New[tea.Msg](64),
		logger:  slog.Default(),
		cfgFile: c,
		cwd:     cwd,
	}

	// Always-available services
	a.agents = agents.NewDetector()
	a.artifacts = artifacts.NewSource()

	// Best-effort AWS service — missing creds is not fatal.
	if aws, err := awssvc.New(ctx); err == nil {
		a.aws = aws
		// Construct SDK clients used by the deploy driver from the same
		// resolved config.
		a.awsCfg = aws.Config()
		a.cfn = cfnclient.NewFromConfig(a.awsCfg)
		a.ec2 = ec2client.NewFromConfig(a.awsCfg)
		a.eks = eksclient.NewFromConfig(a.awsCfg)
	} else {
		slog.Info("app.aws_unavailable", "err", err)
	}

	return a, nil
}

// AttachProgram is retained as a no-op for callers that pre-date the
// channel-based broker path. Workspace.Events() is the canonical pull
// path; the program reference was previously stored to allow direct
// p.Send() calls but is no longer needed. Kept on the API so tests
// don't break, but the value is intentionally discarded.
func (a *App) AttachProgram(p *tea.Program) { _ = p }

// SetHandoff is called by the UI to record the chosen exec target
// before tea.Quit. Goroutine-safe.
func (a *App) SetHandoff(c HandoffCommand) {
	a.handoffMu.Lock()
	defer a.handoffMu.Unlock()
	a.handoff = c
}

// HandoffCommand returns the user's chosen post-exit exec target.
func (a *App) HandoffCommand() HandoffCommand {
	a.handoffMu.Lock()
	defer a.handoffMu.Unlock()
	return a.handoff
}

// SetGoodbye records the message main.go should print after the
// alt-screen tears down. Used to surface kubectl/agent commands the
// user might want to run themselves.
func (a *App) SetGoodbye(msg string) {
	a.handoffMu.Lock()
	defer a.handoffMu.Unlock()
	a.goodbye = msg
}

// Goodbye returns the post-TUI farewell string.
func (a *App) Goodbye() string {
	a.handoffMu.Lock()
	defer a.handoffMu.Unlock()
	return a.goodbye
}

// SetWorkdir records the resolved named-subdir workspace path so
// downstream code (handoff exec) can chdir into it.
func (a *App) SetWorkdir(p string) {
	a.workdirMu.Lock()
	defer a.workdirMu.Unlock()
	a.workdirPath = p
}

// Workdir returns the resolved workspace path. "" means not yet ready.
func (a *App) Workdir() string {
	a.workdirMu.Lock()
	defer a.workdirMu.Unlock()
	return a.workdirPath
}

// SetAgentBin records the binary name for the chosen agent CLI.
func (a *App) SetAgentBin(name string) {
	a.agentMu.Lock()
	defer a.agentMu.Unlock()
	a.agentBin = name
}

// AgentBin returns the resolved agent CLI name. "" means none installed.
func (a *App) AgentBin() string {
	a.agentMu.Lock()
	defer a.agentMu.Unlock()
	return a.agentBin
}

// Workspace returns the read-only façade exposed to the UI.
func (a *App) Workspace() workspace.Workspace { return appWorkspace{a: a, sub: a.events.Subscribe(a.ctx)} }

// Shutdown is the §9.2 contract. Idempotent. Safe to call from defer.
func (a *App) Shutdown() {
	a.once.Do(func() {
		a.cancel()
		a.serviceWG.Wait()
		a.events.Close()
	})
}

// appWorkspace is the concrete Workspace implementation. Constructing
// per-Workspace lets tests pass a different sub channel; production
// always pulls one channel from the broker.
type appWorkspace struct {
	a   *App
	sub <-chan tea.Msg
}

func (w appWorkspace) Config() config.Config              { return w.a.cfgFile }
func (w appWorkspace) Logger() *slog.Logger                { return w.a.logger }
func (w appWorkspace) Events() <-chan tea.Msg              { return w.sub }
func (w appWorkspace) AWS() feature.AWSService             { return w.a.aws }
func (w appWorkspace) Agents() feature.AgentDetector       { return w.a.agents }
func (w appWorkspace) Artifacts() feature.ArtifactSource   { return w.a.artifacts }
func (w appWorkspace) Cwd() string                         { return w.a.cwd }
func (w appWorkspace) Version() string                     { return w.a.cfg.Version }
func (w appWorkspace) Workdir() string                     { return w.a.Workdir() }
func (w appWorkspace) AgentBin() string                    { return w.a.AgentBin() }
func (w appWorkspace) SetHandoff(bin string, args []string, cwd string) {
	w.a.SetHandoff(HandoffCommand{Bin: bin, Args: args, Cwd: cwd})
}
func (w appWorkspace) SetHandoffWithPre(bin string, args []string, cwd string, preBin string, preArgs []string, preDesc string) {
	cmd := HandoffCommand{Bin: bin, Args: args, Cwd: cwd}
	if preBin != "" {
		cmd.PreExec = []PreExecStep{{Bin: preBin, Args: preArgs, Description: preDesc}}
	}
	w.a.SetHandoff(cmd)
}

func (w appWorkspace) SetGoodbye(s string) { w.a.SetGoodbye(s) }
func (w appWorkspace) SetWorkdir(s string) { w.a.SetWorkdir(s) }
func (w appWorkspace) SetAgentBin(s string) { w.a.SetAgentBin(s) }

// DeployDriver returns a façade that wraps feature/deploy.Driver. nil
// when AWS isn't configured — the deploy page falls back to simulated
// ticks in that case (so screenshots / demos still render).
func (w appWorkspace) DeployDriver() *workspace.DeployDriverProxy {
	if w.a.cfn == nil || w.a.ec2 == nil || w.a.eks == nil {
		return nil
	}
	drv := deployfeat.NewDriver(w.a.cfn, w.a.ec2, w.a.eks)
	return &workspace.DeployDriverProxy{
		Start: func(ctx context.Context, params workspace.DeployParams, events chan<- workspace.DeployEvent) error {
			// Translate facade types into the real ones, run, translate back.
			featEvents := make(chan deployfeat.PhaseEvent, 64)
			go func() {
				for ev := range featEvents {
					select {
					case events <- workspace.DeployEvent{Phase: ev.Phase, Status: ev.Status, Message: ev.Message, At: ev.At}:
					case <-ctx.Done():
						return
					}
				}
				close(events)
			}()
			return drv.Run(ctx, deployfeat.Params{
				Region:        params.Region,
				StackName:     params.StackName,
				Stage:         params.Stage,
				Scope:         params.Scope,
				VPCID:         params.VPCID,
				SubnetIDs:     params.SubnetIDs,
				VPCEndpoints:  params.VPCEndpoints,
				MAVersion:     params.MAVersion,
				TLSMode:       params.TLSMode,
				PCAARN:        params.PCAARN,
				Namespace:     params.Namespace,
				ReleaseName:   params.ReleaseName,
				EKSAccessARNs: params.EKSAccessARNs,
				TemplateBody:  params.TemplateBody,
				HelmChartPath: params.HelmChartPath,
				CallerARN:     params.CallerARN,
			}, featEvents)
		},
	}
}
