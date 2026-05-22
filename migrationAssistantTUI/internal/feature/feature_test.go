package feature_test

import (
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

func TestStagesFromExports_BasicShape(t *testing.T) {
	t.Parallel()
	exports := []feature.CFNExport{
		{Name: "MigrationsExportString-dev-us-east-1"},
		{Name: "MigrationsExportString-dev-us-east-1-Bucket"},
		{Name: "MigrationsExportString-prod-us-east-1"},
	}
	got := feature.StagesFromExports(exports, "us-east-1")
	require.Equal(t, []string{"dev", "prod"}, got)
}

func TestStagesFromExports_FiltersOtherRegions(t *testing.T) {
	t.Parallel()
	exports := []feature.CFNExport{
		{Name: "MigrationsExportString-dev-us-west-2"},
		{Name: "MigrationsExportString-prod-us-east-1"},
	}
	got := feature.StagesFromExports(exports, "us-east-1")
	require.Equal(t, []string{"prod"}, got)
}

func TestStagesFromExports_EmptyRegion(t *testing.T) {
	t.Parallel()
	exports := []feature.CFNExport{
		{Name: "MigrationsExportString-dev-us-east-1"},
	}
	require.Empty(t, feature.StagesFromExports(exports, ""))
}

func TestStagesFromExports_IgnoresNonMatching(t *testing.T) {
	t.Parallel()
	exports := []feature.CFNExport{
		{Name: "Random-thing"},
		{Name: "MigrationsExportString-staging-eu-west-1"},
	}
	got := feature.StagesFromExports(exports, "us-east-1")
	require.Empty(t, got)
}

func TestStagesFromExports_DeDupes(t *testing.T) {
	t.Parallel()
	exports := []feature.CFNExport{
		{Name: "MigrationsExportString-dev-us-east-1-A"},
		{Name: "MigrationsExportString-dev-us-east-1-B"},
		{Name: "MigrationsExportString-dev-us-east-1-C"},
	}
	got := feature.StagesFromExports(exports, "us-east-1")
	require.Equal(t, []string{"dev"}, got)
}
