// Package versioncheck implements the launch-time TUI version check
// (UX.md §0.5) and the agent-CLI version check (§0.6).
//
// All checks are best-effort: network errors degrade silently — the TUI
// MUST never block on a version check (locked **r6**).
package versioncheck

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Delta classifies the version gap (UX.md §0.5/§0.6).
type Delta string

const (
	DeltaNone    Delta = ""
	DeltaPatch   Delta = "patch"
	DeltaMinor   Delta = "minor"
	DeltaMajor   Delta = "major"
	DeltaAhead   Delta = "ahead"
	DeltaUnknown Delta = "unknown"
)

// Result is the verdict for one comparison.
type Result struct {
	Local         string
	Latest        string
	Delta         Delta
	SkippedByUser bool
}

// Checker performs both check kinds. Set HTTP for tests.
type Checker struct {
	HTTP      *http.Client
	GitHubURL string   // override for tests; defaults to opensearch-migrations latest
	Skip      []string // versions the user has chosen to "always skip"
}

const defaultGHURL = "https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest"

// New returns a Checker with sensible defaults.
func New() *Checker {
	return &Checker{
		HTTP:      &http.Client{Timeout: 5 * time.Second},
		GitHubURL: defaultGHURL,
	}
}

// TUI checks the current TUI version against the latest GitHub release.
func (c *Checker) TUI(ctx context.Context, local string) (Result, error) {
	if c.HTTP == nil {
		c.HTTP = &http.Client{Timeout: 5 * time.Second}
	}
	url := c.GitHubURL
	if url == "" {
		url = defaultGHURL
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Result{Local: local, Delta: DeltaUnknown}, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return Result{Local: local, Delta: DeltaUnknown}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return Result{Local: local, Delta: DeltaUnknown}, fmt.Errorf("github HTTP %d", resp.StatusCode)
	}
	var rel struct {
		TagName string `json:"tag_name"`
		Name    string `json:"name"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return Result{Local: local, Delta: DeltaUnknown}, err
	}
	latest := strings.TrimPrefix(rel.TagName, "v")
	if latest == "" {
		latest = strings.TrimPrefix(rel.Name, "v")
	}
	res := Result{Local: local, Latest: latest, Delta: Compare(local, latest)}
	if slices.Contains(c.Skip, latest) {
		res.SkippedByUser = true
	}
	return res, nil
}

// Compare returns the delta between two semvers. See UX.md §0.6 rules.
func Compare(local, latest string) Delta {
	if local == "" || latest == "" {
		return DeltaUnknown
	}
	lo, ok1 := parseSemver(local)
	la, ok2 := parseSemver(latest)
	if !ok1 || !ok2 {
		return DeltaUnknown
	}
	switch {
	case lo[0] > la[0] || (lo[0] == la[0] && lo[1] > la[1]) || (lo == la):
	}
	if lo == la {
		return DeltaNone
	}
	if lo[0] > la[0] || (lo[0] == la[0] && lo[1] > la[1]) || (lo[0] == la[0] && lo[1] == la[1] && lo[2] > la[2]) {
		return DeltaAhead
	}
	if lo[0] != la[0] {
		return DeltaMajor
	}
	if lo[1] != la[1] {
		return DeltaMinor
	}
	return DeltaPatch
}

func parseSemver(s string) ([3]int, bool) {
	s = strings.TrimSpace(s)
	re := regexp.MustCompile(`^v?(\d+)\.(\d+)\.(\d+)`)
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

// ErrSkipped is returned when a user has previously chosen to silence
// the prompt for a specific version.
var ErrSkipped = errors.New("version check silenced for this version")
