package handoffbrief

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestWriteFrontmatterAndBody(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	b := Brief{
		MAVersion: "3.2.1", AWSAccount: "111", Region: "us-east-1",
		EKSCluster: "migration-eks-cluster-dev-us-east-1",
		Namespace:  "migration-assistant", Stage: "dev",
		Source: Source{Endpoint: "https://es.example:9200", Engine: "Elasticsearch", EngineVersion: "7.10", AuthKeychainID: "ma-cli-source-creds-abc"},
		Target: Target{Type: "new-opensearch-domain"},
	}
	err := Write(dir, b, "Migrate prod search index to OpenSearch 2.x")
	require.NoError(t, err)

	out, err := os.ReadFile(filepath.Join(dir, "HANDOFF.md"))
	require.NoError(t, err)
	s := string(out)
	require.True(t, strings.HasPrefix(s, "---\n"))
	require.Contains(t, s, "ma_version: 3.2.1")
	require.Contains(t, s, "aws_account: \"111\"")
	require.Contains(t, s, "auth_keychain_id: ma-cli-source-creds-abc")
	require.Contains(t, s, "# Migration goal")
	require.Contains(t, s, "Migrate prod search index to OpenSearch 2.x")
}

func TestSkippedIntentNotesUnknown(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	require.NoError(t, Write(dir, Brief{MAVersion: "3.2.1"}, ""))
	out, _ := os.ReadFile(filepath.Join(dir, "HANDOFF.md"))
	require.Contains(t, string(out), "user skipped intent capture")
}
