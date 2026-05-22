// Package launch performs the at-launch side-effects every TUI session
// needs: ensure the named-subdir workspace exists, write/refresh the
// state file, and (post-deploy) generate HANDOFF.md + the agent skill
// kit. Centralizing this means the UI layer doesn't sprinkle
// EnsureLayout / SaveState / Install calls across pages.
//
// Strict rule (PLAN §3): pages call these helpers via the workspace
// façade, not directly. Tests can substitute a fake.
package launch

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/handoffbrief"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/skillkit"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/workdir"
)

// Result is what PrepareWorkdir returns once the named subdir is ready.
type Result struct {
	Path  string         // resolved workdir, e.g. ./opensearch-migration-123-us-east-1
	State *workdir.State // current state snapshot, nil if fresh
	Guard workdir.Guard
}

// PrepareWorkdir resolves + creates the named subdir for (account, region, version)
// under cwd, and returns the guard verdict. Idempotent — never destroys
// existing files.
//
// Errors:
//   - cwd is on the deny-list (workdir.ErrUnsafeCwd)
//   - cwd not writable
func PrepareWorkdir(cwd, account, region, maVersion string) (Result, error) {
	wd, err := workdir.Resolve(cwd, account, region)
	if err != nil {
		return Result{}, err
	}
	if err := workdir.EnsureLayout(wd); err != nil {
		return Result{}, fmt.Errorf("ensure layout: %w", err)
	}
	guard, st, err := workdir.Inspect(wd, account, region, maVersion)
	if err != nil {
		return Result{Path: wd, Guard: guard}, err
	}
	return Result{Path: wd, State: st, Guard: guard}, nil
}

// MarkStarted writes a fresh in-progress state file. Called when the
// user accepts the review screen and clicks launch.
func MarkStarted(workdirPath string, account, region, maVersion, tuiVersion, stage, mode string) error {
	return workdir.SaveState(workdirPath, workdir.State{
		AccountID:  account,
		Region:     region,
		MAVersion:  maVersion,
		TUIVersion: tuiVersion,
		Stage:      stage,
		Mode:       mode,
		Status:     "in_progress",
	})
}

// MarkInstalled flips the state file to "installed" with the deploy
// timestamp. Called when the deploy goroutine emits CompletedMsg.
func MarkInstalled(workdirPath string, account, region, maVersion, tuiVersion, stage, mode, agent string) error {
	st, err := workdir.LoadState(workdirPath)
	if err != nil {
		return err
	}
	if st == nil {
		st = &workdir.State{}
	}
	st.AccountID = account
	st.Region = region
	st.MAVersion = maVersion
	st.TUIVersion = tuiVersion
	st.Stage = stage
	st.Mode = mode
	st.Agent = agent
	st.Status = "installed"
	st.InstalledAt = time.Now().UTC().Truncate(time.Second)
	return workdir.SaveState(workdirPath, *st)
}

// MarkFailed records a failed deploy so the next launch's resume prompt
// can offer recovery options.
func MarkFailed(workdirPath string) error {
	st, err := workdir.LoadState(workdirPath)
	if err != nil || st == nil {
		return err
	}
	st.Status = "failed"
	return workdir.SaveState(workdirPath, *st)
}

// WriteHandoffBrief renders HANDOFF.md for the agent (or just for the
// user's reference in manual mode). YAML frontmatter + free-text body
// (UX.md §12.4).
func WriteHandoffBrief(workdirPath string, b handoffbrief.Brief, goal string) error {
	return handoffbrief.Write(workdirPath, b, goal)
}

// InstallSkillKit installs the per-agent adapter under workdirPath.
// Best-effort: a missing bundle is reported as an error but doesn't
// prevent handoff (agent can still run; user just won't have skills).
func InstallSkillKit(ctx context.Context, workdirPath string, agent skillkit.Agent, bundleTar string) error {
	if bundleTar == "" {
		return errors.New("bundle tar is required (caller fetches via Artifacts.FetchAtTag)")
	}
	if _, err := os.Stat(bundleTar); err != nil {
		return fmt.Errorf("bundle %s missing: %w", bundleTar, err)
	}
	return skillkit.Install(workdirPath, agent, bundleTar)
}

// FetchArtifacts pulls the full set of artifacts a deploy needs:
// CFN template (Create-VPC or Import-VPC), helm chart tarball, and
// the agent skill bundle.
//
// Returns absolute paths in the workdir's `artifacts/` subdir.
func FetchArtifacts(ctx context.Context, src feature.ArtifactSource, workdirPath, tag, scope string) (Artifacts, error) {
	if src == nil {
		return Artifacts{}, errors.New("artifact source not configured")
	}
	dir := filepath.Join(workdirPath, "artifacts")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return Artifacts{}, err
	}
	out := Artifacts{}

	// CFN template — name depends on scope.
	cfnName := "Migration-Assistant-Infra-Create-VPC-eks.template.json"
	if scope == "import-vpc" {
		cfnName = "Migration-Assistant-Infra-Import-VPC-eks.template.json"
	}
	if scope != "skip-cfn" {
		out.CFNTemplate = filepath.Join(dir, cfnName)
		if _, err := src.FetchAtTag(ctx, tag, cfnName, out.CFNTemplate); err != nil {
			return out, fmt.Errorf("fetch CFN template: %w", err)
		}
	}

	// Helm chart.
	chartName := "migration-assistant-" + tag + ".tgz"
	out.HelmChart = filepath.Join(dir, chartName)
	if _, err := src.FetchAtTag(ctx, tag, chartName, out.HelmChart); err != nil {
		return out, fmt.Errorf("fetch helm chart: %w", err)
	}

	// Kiro skill bundle (also used as the agent-agnostic source for
	// Claude Code per UX.md §0.2).
	bundleName := "kiro-assistant.tar.gz"
	out.SkillBundle = filepath.Join(dir, bundleName)
	if _, err := src.FetchAtTag(ctx, tag, bundleName, out.SkillBundle); err != nil {
		// Non-fatal — skill kit is post-handoff convenience.
		out.SkillBundleErr = err
	}

	return out, nil
}

// Artifacts is the bundle of paths FetchArtifacts produced.
type Artifacts struct {
	CFNTemplate    string // empty for skip-cfn
	HelmChart      string
	SkillBundle    string // may be empty if SkillBundleErr is set
	SkillBundleErr error
}
