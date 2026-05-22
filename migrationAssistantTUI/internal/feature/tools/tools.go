// Package tools detects required external CLIs (helm, kubectl, aws, git,
// docker) and offers best-effort installers for the ones bootstrap-kiro-agent.sh
// auto-installs (helm, kiro-cli).
//
// Style rule (PLAN §3): no charm.land/ imports here — pure domain.
//
// The TUI's existing `agents` package handles kiro-cli / claude-code DETECTION
// only; it does not install them. bootstrap-kiro-agent.sh installs both helm
// and kiro-cli as part of its 5-phase boot. To retire that script, the TUI
// must be the source of truth for both detection AND installation.
//
// v1 scope (locked under UX.md §C re-implement-in-Go):
//   - Detect helm, kubectl, aws, git, docker, kiro-cli, claude-code on $PATH.
//   - Install helm via the official `get-helm-3` script (matches bootstrap.sh).
//   - Install kiro-cli via the published kiro.dev download (matches bootstrap.sh).
//   - kubectl/aws/git/docker are NOT auto-installed: they're OS-package-manager
//     concerns and the TUI just surfaces the gap with a remediation hint.
package tools

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

// Tool re-exports feature.Tool so callers in this package can refer to
// the unqualified name. The canonical type lives in `feature`.
type Tool = feature.Tool

// All is the canonical list of tools the TUI cares about, in the order
// the welcome screen renders them.
var All = []Tool{
	{Name: "aws", Required: true, Installable: false},
	{Name: "kubectl", Required: true, Installable: false},
	{Name: "helm", Required: true, Installable: true},
	{Name: "git", Required: false, Installable: false},
	{Name: "docker", Required: false, Installable: false},
	{Name: "kiro-cli", Required: false, Installable: true},
	{Name: "claude-code", Required: false, Installable: false},
}

// Detector is the production implementation of feature.ToolDetector.
type Detector struct {
	// HTTPClient is used for the kiro-cli installer download. nil falls
	// back to http.DefaultClient.
	HTTPClient *http.Client
}

// NewDetector returns a Detector with sensible defaults.
func NewDetector() *Detector { return &Detector{} }

// Detect walks `All` and returns a populated copy.
func (d *Detector) Detect(ctx context.Context) ([]Tool, error) {
	out := make([]Tool, len(All))
	for i, t := range All {
		out[i] = t
		path, err := exec.LookPath(t.Name)
		if err != nil {
			continue
		}
		out[i].Path = path
		out[i].Version = probeVersion(ctx, path, t.Name)
	}
	return out, nil
}

// probeVersion runs `<bin> --version` (or the documented variant) with a
// short deadline and returns the first non-empty line.
func probeVersion(ctx context.Context, path, name string) string {
	cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	args := []string{"--version"}
	switch name {
	case "helm":
		args = []string{"version", "--short"}
	case "kubectl":
		args = []string{"version", "--client", "--short"}
	}
	cmd := exec.CommandContext(cctx, path, args...)
	out, err := cmd.CombinedOutput()
	if err != nil && len(out) == 0 {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			return line
		}
	}
	return ""
}

// InstallProgress is one step of an installation. Status ∈
// {"started","progress","completed","failed"}.
type InstallProgress struct {
	Tool    string
	Status  string
	Message string
}

// InstallHelm runs the official get-helm-3 installer. Mirrors phase 1 of
// bootstrap-kiro-agent.sh exactly so the experience is identical.
//
// Side effect: writes /usr/local/bin/helm via sudo if not already on
// PATH. The installer script handles the architecture detection.
func InstallHelm(ctx context.Context, emit func(InstallProgress)) error {
	if emit == nil {
		emit = func(InstallProgress) {}
	}
	if p, err := exec.LookPath("helm"); err == nil {
		emit(InstallProgress{Tool: "helm", Status: "completed", Message: "already installed at " + p})
		return nil
	}
	emit(InstallProgress{Tool: "helm", Status: "started", Message: "downloading get-helm-3 from helm.sh"})

	tmp, err := os.CreateTemp("", "get-helm-3-*.sh")
	if err != nil {
		return fmt.Errorf("tempfile: %w", err)
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()

	req, err := http.NewRequestWithContext(ctx,
		"GET",
		"https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3",
		nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("download get-helm-3: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("download get-helm-3: HTTP %d", resp.StatusCode)
	}
	if _, err := io.Copy(tmp, resp.Body); err != nil {
		return fmt.Errorf("write get-helm-3: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmp.Name(), 0o755); err != nil {
		return err
	}
	emit(InstallProgress{Tool: "helm", Status: "progress", Message: "running installer"})

	cmd := exec.CommandContext(ctx, "bash", tmp.Name())
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("get-helm-3: %w (output: %s)", err, strings.TrimSpace(string(out)))
	}
	if _, err := exec.LookPath("helm"); err != nil {
		return fmt.Errorf("get-helm-3 ran but helm still not on PATH: %w", err)
	}
	emit(InstallProgress{Tool: "helm", Status: "completed", Message: "helm installed"})
	return nil
}

// InstallKiroCLI fetches the kiro-cli binary from kiro.dev and writes
// it to /usr/local/bin/kiro-cli (sudo). Mirrors phase 2 of
// bootstrap-kiro-agent.sh.
//
// Returns an error if the platform is unsupported or sudo is unavailable.
func InstallKiroCLI(ctx context.Context, emit func(InstallProgress)) error {
	if emit == nil {
		emit = func(InstallProgress) {}
	}
	if p, err := exec.LookPath("kiro-cli"); err == nil {
		emit(InstallProgress{Tool: "kiro-cli", Status: "completed", Message: "already installed at " + p})
		return nil
	}
	arch := runtime.GOARCH
	switch arch {
	case "amd64":
		arch = "x64"
	case "arm64":
		// kiro publishes "arm64"
	default:
		return fmt.Errorf("kiro-cli: unsupported architecture %q", arch)
	}
	osName := runtime.GOOS
	if osName != "linux" && osName != "darwin" {
		return fmt.Errorf("kiro-cli: unsupported OS %q", osName)
	}
	url := fmt.Sprintf("https://kiro.dev/downloads/latest/%s/%s/kiro-cli", osName, arch)
	emit(InstallProgress{Tool: "kiro-cli", Status: "started", Message: "downloading from " + url})

	tmp, err := os.CreateTemp("", "kiro-cli-*")
	if err != nil {
		return fmt.Errorf("tempfile: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		tmp.Close()
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		tmp.Close()
		return fmt.Errorf("download kiro-cli: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		tmp.Close()
		return fmt.Errorf("download kiro-cli: HTTP %d", resp.StatusCode)
	}
	if _, err := io.Copy(tmp, resp.Body); err != nil {
		tmp.Close()
		return fmt.Errorf("write kiro-cli: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmpPath, 0o755); err != nil {
		return err
	}
	dest := "/usr/local/bin/kiro-cli"
	emit(InstallProgress{Tool: "kiro-cli", Status: "progress", Message: "installing to " + dest + " (may prompt for sudo)"})

	// Try without sudo first (developer machines often have writable /usr/local/bin).
	if err := os.Rename(tmpPath, dest); err == nil {
		emit(InstallProgress{Tool: "kiro-cli", Status: "completed", Message: "kiro-cli installed"})
		return nil
	}
	// Fall back to sudo cp.
	cmd := exec.CommandContext(ctx, "sudo", "cp", tmpPath, dest)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("sudo cp kiro-cli to %s: %w (output: %s)", dest, err, strings.TrimSpace(string(out)))
	}
	cmd = exec.CommandContext(ctx, "sudo", "chmod", "+x", dest)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("sudo chmod kiro-cli: %w (output: %s)", err, strings.TrimSpace(string(out)))
	}
	if _, err := exec.LookPath("kiro-cli"); err != nil {
		return fmt.Errorf("kiro-cli copied to %s but not on PATH", dest)
	}
	emit(InstallProgress{Tool: "kiro-cli", Status: "completed", Message: "kiro-cli installed"})
	return nil
}

// MissingHint returns a human-readable remediation string for tools the
// TUI does not auto-install (kubectl, aws, git, docker).
func MissingHint(name string) string {
	switch name {
	case "kubectl":
		switch runtime.GOOS {
		case "darwin":
			return "brew install kubectl"
		case "linux":
			return "curl -LO https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/" + runtime.GOARCH + "/kubectl && sudo install -m 0755 kubectl /usr/local/bin/"
		}
	case "aws":
		return "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
	case "git":
		switch runtime.GOOS {
		case "darwin":
			return "brew install git"
		case "linux":
			return "your distro's package manager (apt-get install git / dnf install git)"
		}
	case "docker":
		return "https://docs.docker.com/get-docker/"
	}
	return ""
}

// EnsureBinDir is a small helper for tests / installers that want to
// confirm a destination directory is writable before downloading.
func EnsureBinDir(p string) error {
	dir := filepath.Dir(p)
	info, err := os.Stat(dir)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return fmt.Errorf("%s is not a directory", dir)
	}
	return nil
}

// =============================================================================
// feature.ToolInstaller adapters
// =============================================================================
//
// The two installers above (InstallHelm / InstallKiroCLI) are kept as
// standalone functions for testability. The Installer struct below
// implements feature.ToolInstaller by routing on tool name and bridging
// the local InstallProgress callback to feature.InstallEvent on a channel.
//
// Detection (Detector.Detect) is read-only and never calls Install — the
// welcome page surfaces missing tools and only invokes Install when the
// user confirms.

// Installer dispatches Install requests to the right per-tool routine.
// Implements feature.ToolInstaller.
type Installer struct{}

// NewInstaller returns the production installer that knows about helm
// and kiro-cli. Always non-nil; safe to call from app wiring.
func NewInstaller() *Installer { return &Installer{} }

// Supports lists the tool names this installer can handle. Matches the
// `Installable: true` entries in `All`.
func (Installer) Supports() []string { return []string{"helm", "kiro-cli"} }

// Install fetches and links `tool` to PATH. Routes to InstallHelm /
// InstallKiroCLI by name and translates their InstallProgress stream
// onto the events channel as feature.InstallEvent values.
//
// If events is nil the installer runs silently (still blocks until done).
func (Installer) Install(ctx context.Context, tool string, events chan<- feature.InstallEvent) error {
	emit := func(p InstallProgress) {
		if events == nil {
			return
		}
		ev := feature.InstallEvent{
			Tool:    p.Tool,
			Stage:   p.Status,
			Pct:     -1,
			Message: p.Message,
			Done:    p.Status == "completed" || p.Status == "failed",
		}
		// Non-blocking-ish: caller is responsible for draining. A
		// best-effort send keeps the installer from deadlocking if
		// the consumer goes away mid-install.
		select {
		case events <- ev:
		case <-ctx.Done():
		}
	}

	var runErr error
	switch tool {
	case "helm":
		runErr = InstallHelm(ctx, emit)
	case "kiro-cli":
		runErr = InstallKiroCLI(ctx, emit)
	default:
		return fmt.Errorf("tools.Installer: unsupported tool %q (supports: %v)", tool, []string{"helm", "kiro-cli"})
	}

	if runErr != nil && events != nil {
		// Final failure event so consumers don't wait forever.
		select {
		case events <- feature.InstallEvent{
			Tool:    tool,
			Stage:   "failed",
			Pct:     -1,
			Message: runErr.Error(),
			Done:    true,
			Err:     runErr,
		}:
		case <-ctx.Done():
		}
	}
	return runErr
}
