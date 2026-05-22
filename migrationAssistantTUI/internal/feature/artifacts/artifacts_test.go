package artifacts

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// fakeRT routes by hostname so we can test all 3 tiers (release →
// raw → hard fail) in one fixture. body is returned for both 200 cases.
type fakeRT struct {
	HitsRelease   int
	HitsRaw       int
	releaseStatus int
	rawStatus     int
	body          []byte
}

func (f *fakeRT) RoundTrip(req *http.Request) (*http.Response, error) {
	host := req.URL.Host
	switch {
	case strings.Contains(host, "raw.githubusercontent.com"):
		f.HitsRaw++
		return &http.Response{
			StatusCode: f.rawStatus,
			Body:       io.NopCloser(bytes.NewReader(f.body)),
			Header:     http.Header{},
		}, nil
	case strings.Contains(host, "github.com"):
		f.HitsRelease++
		return &http.Response{
			StatusCode: f.releaseStatus,
			Body:       io.NopCloser(bytes.NewReader(f.body)),
			Header:     http.Header{},
		}, nil
	}
	return nil, http.ErrNotSupported
}

func TestFetchAtTag_ReleaseAssetHappyPath(t *testing.T) {
	t.Parallel()
	rt := &fakeRT{releaseStatus: 200, body: []byte("the-asset")}
	src := &Source{HTTP: &http.Client{Transport: rt, Timeout: 5 * time.Second}}
	dst := filepath.Join(t.TempDir(), "ok.tgz")
	used, err := src.FetchAtTag(context.Background(), "3.2.1", "any.tgz", dst)
	require.NoError(t, err)
	require.Contains(t, used, "github.com")
	require.Equal(t, 1, rt.HitsRelease)
	require.Zero(t, rt.HitsRaw)

	got, err := os.ReadFile(dst)
	require.NoError(t, err)
	require.Equal(t, []byte("the-asset"), got)
}

func TestFetchAtTag_RawFallback(t *testing.T) {
	t.Parallel()
	rt := &fakeRT{
		releaseStatus: 404,
		rawStatus:     200,
		body:          []byte("hello-from-raw"),
	}
	src := &Source{
		HTTP:         &http.Client{Transport: rt, Timeout: 5 * time.Second},
		RawRepoPaths: map[string]string{"opensearch-migration-skills.tar.gz": "deployment/skills/x.tar.gz"},
	}
	dst := filepath.Join(t.TempDir(), "out.tar.gz")
	used, err := src.FetchAtTag(context.Background(), "3.2.1", "opensearch-migration-skills.tar.gz", dst)
	require.NoError(t, err)
	require.Contains(t, used, "raw.githubusercontent.com")
	require.Equal(t, 1, rt.HitsRelease)
	require.Equal(t, 1, rt.HitsRaw)

	got, err := os.ReadFile(dst)
	require.NoError(t, err)
	require.Equal(t, []byte("hello-from-raw"), got)
}

func TestFetchAtTag_HardFailWithoutRawPath(t *testing.T) {
	t.Parallel()
	rt := &fakeRT{releaseStatus: 404}
	src := &Source{
		HTTP:         &http.Client{Transport: rt, Timeout: 5 * time.Second},
		RawRepoPaths: map[string]string{}, // no fallback
	}
	dst := filepath.Join(t.TempDir(), "missing.tgz")
	_, err := src.FetchAtTag(context.Background(), "3.2.1", "no-such-asset.tgz", dst)
	require.Error(t, err)
	require.Contains(t, err.Error(), "file an issue")
}

func TestFetchAtTag_RejectsEmptyTag(t *testing.T) {
	t.Parallel()
	src := NewSource()
	dst := filepath.Join(t.TempDir(), "x")
	_, err := src.FetchAtTag(context.Background(), "", "x", dst)
	require.Error(t, err)
	require.Contains(t, err.Error(), "tag is required")
}
