// Package marelease detects the latest Migration Assistant release tag
// on GitHub. Used by the welcome screen to confirm the version before
// the deploy flow tries to fetch artifacts.
//
// Best-effort: GitHub unreachable → returns the pinned default
// (currently 3.2.1). NEVER fatal — UX.md §0.5 says version checks must
// not block launch.
package marelease

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// PinnedDefault is the MA version this TUI was built against.
//
// v1.0 hard-pins to MA 3.2.1 (locked O). When the upstream release is
// newer, we surface the diff in the welcome screen and let the user
// decide; we don't auto-bump because the CFN template + helm chart
// schemas may have changed.
const PinnedDefault = "3.2.1"

// LatestURL is the GitHub releases endpoint we hit. Exposed as a var
// so tests can repoint it at httptest.Server.
var LatestURL = "https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest"

// Latest is the result of fetching the latest GitHub release.
type Latest struct {
	// Tag is the upstream tag (e.g. "3.3.0"). Empty when unknown.
	Tag string
	// Err is the underlying error when the fetch failed; the caller
	// should surface this to the user as a soft warning, not abort.
	Err error
}

// Fetch returns the latest release tag for opensearch-project/opensearch-migrations.
// 5s timeout, no retries. The caller treats nil-Tag as "unknown" and
// falls back to PinnedDefault.
func Fetch(ctx context.Context) Latest {
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodGet, LatestURL, nil)
	if err != nil {
		return Latest{Err: err}
	}
	req.Header.Set("Accept", "application/vnd.github+json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return Latest{Err: err}
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return Latest{Err: fmt.Errorf("github HTTP %d", resp.StatusCode)}
	}
	var rel struct {
		TagName string `json:"tag_name"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return Latest{Err: err}
	}
	return Latest{Tag: strings.TrimPrefix(rel.TagName, "v")}
}
