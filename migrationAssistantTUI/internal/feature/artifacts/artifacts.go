// Package artifacts implements feature.ArtifactSource per UX.md §0.7:
//
//	1. GitHub release asset at the chosen MA tag.
//	2. Tag-pinned raw repo file.
//	3. Hard-fail with the URLs we tried.
//
// Every fallback to (2) is logged with a TODO note so we have a clean
// upstream-promotion backlog.
package artifacts

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

const (
	repoOwner = "opensearch-project"
	repoName  = "opensearch-migrations"

	releasesBase = "https://github.com/" + repoOwner + "/" + repoName + "/releases/download"
	rawBase      = "https://raw.githubusercontent.com/" + repoOwner + "/" + repoName
)

// Source is the v1 artifact fetcher.
type Source struct {
	HTTP *http.Client
	// RawRepoPaths maps artifact name → relative path in the repo, used
	// as the (2) fallback for assets not yet promoted to releases.
	// UX.md §0.7 catalog enumerates these.
	RawRepoPaths map[string]string
}

// NewSource returns a Source with sensible HTTP defaults.
func NewSource() *Source {
	return &Source{
		HTTP: &http.Client{Timeout: 60 * time.Second},
		// Future TODO entries land here as we discover artifacts that
		// don't have a release-asset path yet.
		RawRepoPaths: map[string]string{
			// Per UX.md §0.7: every fallback is tagged TODO.
			"opensearch-migration-skills.tar.gz": "deployment/migration-assistant/skills/opensearch-migration-skills.tar.gz",
			"claude-code-skills.tar.gz":          "deployment/migration-assistant/skills/claude-code-skills.tar.gz",
		},
	}
}

// FetchAtTag downloads `name` for `tag` to `dst`, trying release asset
// first and tag-pinned raw repo as fallback. Returns the source URL used
// (helpful for the §0.7 "fetched via raw repo" log line).
func (s *Source) FetchAtTag(ctx context.Context, tag, name, dst string) (string, error) {
	if tag == "" {
		return "", errors.New("tag is required (UX §0.7: never fetch latest/main)")
	}
	if name == "" {
		return "", errors.New("artifact name is required")
	}

	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", err
	}

	// (1) Release asset
	releaseURL := fmt.Sprintf("%s/%s/%s", releasesBase, tag, name)
	if err := s.tryURL(ctx, releaseURL, dst); err == nil {
		return releaseURL, nil
	} else if !errors.Is(err, errNotFound) {
		return "", fmt.Errorf("release asset %s: %w", releaseURL, err)
	}

	// (2) Raw repo (tag-pinned), only if we know the path
	repoPath, ok := s.RawRepoPaths[name]
	if !ok {
		return "", fmt.Errorf("artifact %q not at tag %s in releases, and no raw-repo fallback is registered (file an issue: https://github.com/%s/%s/issues/new?title=missing+release+asset+for+%s+at+tag+%s)",
			name, tag, repoOwner, repoName, name, tag)
	}
	rawURL := fmt.Sprintf("%s/%s/%s", rawBase, tag, repoPath)
	if err := s.tryURL(ctx, rawURL, dst); err != nil {
		return "", fmt.Errorf("artifact %q not at tag %s in releases or raw repo (tried %s and %s): %w",
			name, tag, releaseURL, rawURL, err)
	}
	slog.Warn("artifact.raw_repo_fallback",
		"name", name, "tag", tag, "url", rawURL,
		"todo", "promote artifact to GitHub release asset (UX.md §0.7)")
	return rawURL, nil
}

var errNotFound = errors.New("artifact not found at this URL")

func (s *Source) tryURL(ctx context.Context, url, dst string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := s.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return errNotFound
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	tmp := dst + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, dst)
}
