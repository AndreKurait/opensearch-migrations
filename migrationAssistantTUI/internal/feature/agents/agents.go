// Package agents implements feature.AgentDetector for v1: kiro-cli +
// claude-code (UX.md §0.6).
//
// Detection is best-effort:
//   - PATH lookup with `exec.LookPath`.
//   - Local version: `<bin> --version`, parsed best-effort.
//   - Latest version (claude-code only): GET registry.npmjs.org/...latest.
//   - Kiro: latest skipped per locked **r10**.
//
// All network calls have short timeouts and are non-fatal.
package agents

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

const (
	npmClaudeURL = "https://registry.npmjs.org/@anthropic-ai/claude-code/latest"
)

// Detector is the v1 implementation.
type Detector struct {
	HTTP *http.Client
	// AgentNames lets tests inject a smaller surface.
	AgentNames []string
}

// NewDetector returns a Detector with sane defaults: 5s HTTP, both v1 agents.
func NewDetector() *Detector {
	return &Detector{
		HTTP:       &http.Client{Timeout: 5 * time.Second},
		AgentNames: []string{"claude-code", "kiro-cli"},
	}
}

// Detect locates each known agent and (best-effort) compares versions.
func (d *Detector) Detect(ctx context.Context) ([]feature.AgentCLI, error) {
	out := make([]feature.AgentCLI, 0, len(d.AgentNames))
	for _, name := range d.AgentNames {
		bin := name
		if name == "claude-code" {
			bin = "claude"
		}
		ag := feature.AgentCLI{Name: name}
		path, err := exec.LookPath(bin)
		if err != nil {
			out = append(out, ag) // Path stays "" → "not installed"
			continue
		}
		ag.Path = path
		ag.LocalVersion = readVersion(ctx, bin)

		switch name {
		case "claude-code":
			ag.LatestVersion = d.fetchLatestNpm(ctx, npmClaudeURL)
		case "kiro-cli":
			// Locked r10: skipped — no clean public version source.
		}
		ag.BehindBy = classifyDelta(ag.LocalVersion, ag.LatestVersion)
		out = append(out, ag)
	}
	return out, nil
}

func readVersion(ctx context.Context, bin string) string {
	cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx, bin, "--version")
	b, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func (d *Detector) fetchLatestNpm(ctx context.Context, url string) string {
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodGet, url, nil)
	if err != nil {
		return ""
	}
	resp, err := d.HTTP.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return ""
	}
	var pkg struct {
		Version string `json:"version"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&pkg); err != nil {
		return ""
	}
	return pkg.Version
}

// classifyDelta returns the version-delta label per UX.md §0.6 rules.
//
//   - Both empty → DeltaUnknown
//   - latest empty → DeltaUnknown (we can't compare)
//   - parse failure → DeltaUnknown
//   - local > latest → DeltaAhead
//   - equal → DeltaNone
//   - patch differs → DeltaPatch
//   - minor differs → DeltaMinor
//   - major differs → DeltaMajor
func classifyDelta(local, latest string) feature.VersionDelta {
	if local == "" || latest == "" {
		return feature.DeltaUnknown
	}
	lo, ok1 := parseSemver(local)
	la, ok2 := parseSemver(latest)
	if !ok1 || !ok2 {
		return feature.DeltaUnknown
	}
	switch {
	case lo[0] > la[0] || (lo[0] == la[0] && lo[1] > la[1]) || (lo[0] == la[0] && lo[1] == la[1] && lo[2] > la[2]):
		return feature.DeltaAhead
	case lo == la:
		return feature.DeltaNone
	case lo[0] != la[0]:
		return feature.DeltaMajor
	case lo[1] != la[1]:
		return feature.DeltaMinor
	default:
		return feature.DeltaPatch
	}
}

// parseSemver extracts MAJOR.MINOR.PATCH from a version string. Strips
// leading "v" and any prerelease/build suffix.
func parseSemver(s string) ([3]int, bool) {
	s = strings.TrimSpace(s)
	// Allow strings like "claude 2.1.140 (foo)"; pick the first digit run.
	re := regexp.MustCompile(`v?(\d+)\.(\d+)\.(\d+)`)
	m := re.FindStringSubmatch(s)
	if len(m) != 4 {
		return [3]int{}, false
	}
	var out [3]int
	for i := 0; i < 3; i++ {
		n, err := strconv.Atoi(m[i+1])
		if err != nil {
			return [3]int{}, false
		}
		out[i] = n
	}
	return out, true
}

// AgentLine renders one agent line for the welcome screen detection
// summary. Kept here (not in styles) so the formatting rule lives next
// to the data shape.
func AgentLine(a feature.AgentCLI) string {
	if a.Path == "" {
		return fmt.Sprintf("%-13s not installed", a.Name)
	}
	if a.LocalVersion == "" {
		return fmt.Sprintf("%-13s installed (version unknown)", a.Name)
	}
	if a.LatestVersion == "" {
		return fmt.Sprintf("%-13s %s  (online version unknown)", a.Name, a.LocalVersion)
	}
	if a.BehindBy == feature.DeltaNone || a.BehindBy == feature.DeltaAhead {
		return fmt.Sprintf("%-13s %s  (latest %s)", a.Name, a.LocalVersion, a.LatestVersion)
	}
	return fmt.Sprintf("%-13s %s  (latest %s — %s behind) ⚠", a.Name, a.LocalVersion, a.LatestVersion, a.BehindBy)
}

// ErrNoAgent is returned when a chosen agent name is not detected.
var ErrNoAgent = errors.New("agent CLI not installed on PATH")
