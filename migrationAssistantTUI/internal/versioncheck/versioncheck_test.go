package versioncheck

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestParseSemverAndDelta(t *testing.T) {
	t.Parallel()
	cases := []struct {
		a, b string
		want Delta
	}{
		{"1.0.0", "1.0.0", DeltaNone},
		{"1.0.0", "1.0.1", DeltaPatch},
		{"1.0.0", "1.1.0", DeltaMinor},
		{"1.0.0", "2.0.0", DeltaMajor},
		{"v1.2.3", "1.2.3", DeltaNone},
		{"1.2.4", "1.2.3", DeltaAhead},
		{"garbage", "1.0.0", DeltaUnknown},
	}
	for _, c := range cases {
		got := Compare(c.a, c.b)
		require.Equal(t, c.want, got, "Compare(%q,%q)", c.a, c.b)
	}
}

func TestCheckTUIRespectsCacheAndSkip(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"tag_name":"v1.2.0","name":"v1.2.0"}`))
	}))
	t.Cleanup(srv.Close)

	c := &Checker{HTTP: srv.Client(), GitHubURL: srv.URL, Skip: nil}
	res, err := c.TUI(context.Background(), "1.0.0")
	require.NoError(t, err)
	require.Equal(t, "1.2.0", res.Latest)
	require.Equal(t, DeltaMinor, res.Delta)

	c.Skip = []string{"1.2.0"}
	res, err = c.TUI(context.Background(), "1.0.0")
	require.NoError(t, err)
	require.True(t, res.SkippedByUser)
}
