package marelease_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/marelease"
)

func TestPinnedDefaultIsSemver(t *testing.T) {
	t.Parallel()
	// We can't predict the exact version forever but it must be at
	// least three dotted numeric components.
	require.Regexp(t, `^\d+\.\d+\.\d+`, marelease.PinnedDefault)
}

// TestFetchHandlesNetworkErrorsCleanly verifies the contract: a network
// failure produces (empty Tag, non-nil Err) — never a panic.
func TestFetchHandlesNetworkErrorsCleanly(t *testing.T) {
	t.Parallel()
	// Cancel immediately so the HTTP call can't even start.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	got := marelease.Fetch(ctx)
	require.Empty(t, got.Tag, "context-cancelled fetch must yield empty Tag")
	require.Error(t, got.Err)
}

// TestFetchAcceptsTaggedReleases proves the JSON parsing accepts the
// usual GitHub release shape, by pointing the http.DefaultClient at a
// local httptest server.
//
// We can't override DefaultClient directly without invasive changes, so
// instead this test only asserts the documented contract holds when
// there's no network — see TestFetchHandlesNetworkErrorsCleanly.
func TestFetchEmptyContextDoesNotPanic(t *testing.T) {
	t.Parallel()
	// nolint:staticcheck — the SA1012 warning is the point of the test.
	require.NotPanics(t, func() {
		_ = marelease.Fetch(context.Background())
	})
}

func TestFetchAgainstFakeServer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"tag_name":"v3.4.0","name":"3.4.0"}`))
	}))
	t.Cleanup(srv.Close)

	original := marelease.LatestURL
	t.Cleanup(func() { marelease.LatestURL = original })
	marelease.LatestURL = srv.URL

	got := marelease.Fetch(context.Background())
	require.NoError(t, got.Err)
	require.Equal(t, "3.4.0", got.Tag, "must strip the leading 'v' from the GitHub tag")
}

func TestFetchAgainstFakeServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	t.Cleanup(srv.Close)
	original := marelease.LatestURL
	t.Cleanup(func() { marelease.LatestURL = original })
	marelease.LatestURL = srv.URL

	got := marelease.Fetch(context.Background())
	require.Error(t, got.Err)
	require.Empty(t, got.Tag)
}
